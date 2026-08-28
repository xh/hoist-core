/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.entra

import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.ConfidentialClientApplication
import com.microsoft.aad.msal4j.IClientCredential
import com.microsoft.aad.msal4j.IConfidentialClientApplication
import grails.async.Promise
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.cache.Cache
import io.xh.hoist.config.ConfigService
import io.xh.hoist.directory.DirectoryService
import io.xh.hoist.exception.HttpException
import io.xh.hoist.http.JSONClient
import io.xh.hoist.util.ErrorOr
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.core5.net.URIBuilder

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.regex.Pattern

import static grails.async.Promises.task
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static java.util.Collections.emptyMap

/**
 * Service to query a Microsoft Entra ID tenant for users, groups, and group memberships via the
 * Microsoft Graph API.
 *
 * <p>An optional alternative to {@link io.xh.hoist.ldap.LdapService} for applications whose
 * corporate directory lives in Entra ID (formerly Azure AD). Both services can resolve
 * directory-group role memberships for {@link io.xh.hoist.role.provided.DefaultRoleService},
 * via the common {@link DirectoryService} interface. Groups are identified by their Entra ID
 * object ID (GUID), which is unique and stable through group renames.
 *
 * <p>Queries use app-only (client credentials) auth. The backing Entra ID app registration must
 * hold the following Microsoft Graph <b>application</b> permissions, granted with admin consent:
 * <ul>
 *   <li>`GroupMember.Read.All` - group lookups and transitive membership resolution.</li>
 *   <li>`User.Read.All` - user lookups.</li>
 * </ul>
 *
 * <p>Requires the following soft configs:
 * <ul>
 *   <li>`xhEntraIdConfig` - query settings and master switch. See {@link EntraIdConfig} for
 *       all supported options and their defaults.</li>
 *   <li>`xhEntraTenantId` - tenant ID (GUID) of the Entra ID tenant.</li>
 *   <li>`xhEntraClientId` - client ID (GUID) of the app registration.</li>
 * </ul>
 *
 * <p>Plus one of two credentials for the app registration, either:
 * <ul>
 *   <li>`xhEntraClientPfx` (+ `xhEntraClientPfxPassword`) - a base64-encoded PKCS#12 (.pfx)
 *       bundle holding the certificate uploaded to the app registration, its chain (if any),
 *       and its private key. The recommended credential type for production - see
 *       {@link EntraClientCredentials}.</li>
 *   <li>`xhEntraClientSecret` - a client secret. Simpler to provision, but Microsoft
 *       recommends against secrets for production use.</li>
 * </ul>
 * When both are configured, the certificate is used.
 *
 * <p>The tenant ID and client ID are standalone configs so that other server-side subsystems
 * can share them. Deployments can also override them per environment via instance configs /
 * environment variables. Apps whose clients need them pre-auth (e.g. for OAuth login) should
 * relay them via their AuthenticationService's `getClientConfig()`.
 *
 * <p>Results are cached per query for `xhEntraIdConfig.cacheExpireSecs`. Queries run with
 * `strictMode = false` will log and absorb failures, so callers can receive partial results.
 * Graph throttling (HTTP 429) and server errors are retried a bounded number of times before
 * the query fails.
 */
@CompileStatic
class EntraIdService extends BaseService implements DirectoryService {

    ConfigService configService

    static clearCachesConfigs = [
        'xhEntraIdConfig', 'xhEntraTenantId', 'xhEntraClientId', 'xhEntraClientSecret',
        'xhEntraClientPfx', 'xhEntraClientPfxPassword'
    ]

    static final String GRAPH_BASE_URL = 'https://graph.microsoft.com/v1.0'

    private static final Set<String> GRAPH_SCOPES = ['https://graph.microsoft.com/.default'] as Set
    private static final Pattern GUID_PATTERN =
        Pattern.compile(/(?i)^[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}$/)
    private static final int MAX_RETRIES = 2
    private static final long RETRY_DELAY_MS = 2000

    /** Max lookups run concurrently by {@link #parallelLookup} - see that method for context. */
    private static final int MAX_PARALLEL_LOOKUPS = 25

    /** Cached marker for a user lookup that found no match. */
    private static final String NO_MATCH = 'NO_MATCH'

    private Cache<String, Object> queryCache = createCache(
        name: 'queryCache',
        expireTime: { config.cacheExpireSecs * SECONDS }
    )

    private IConfidentialClientApplication _msalClient
    private JSONClient _jsonClient

    void init() {
        // Warm the Graph token eagerly so config/credential problems surface in the log at
        // startup, rather than on the first role-resolution refresh. Failure here does not
        // block startup - queries will retry token acquisition on demand.
        if (enabled) {
            try {
                acquireAccessToken()
                logInfo('Acquired Microsoft Graph access token', [tenantId: tenantId, authMode: authMode])
            } catch (Exception e) {
                logError('Failed to acquire Microsoft Graph access token on startup', e)
            }
        }
        super.init()
    }

    /**
     * True if configured for use - all query methods below will throw if called when false.
     */
    boolean getEnabled() {
        config.enabled
    }

    //------------------------
    // Users
    //------------------------
    /**
     * Lookup a single user by object ID or userPrincipalName.
     * @return matching user, or null if not found.
     */
    EntraUser lookupUser(String idOrUpn) {
        withDebug(["Looking up user", [user: idOrUpn]]) {
            ensureEnabled()
            String key = "user|${idOrUpn.toLowerCase()}"
            def cached = queryCache.get(key)
            // Type check, not identity - a replicated cache would return deserialized copies.
            if (cached != null) return cached instanceof String ? null : cached as EntraUser

            def data = graphGetObject(
                "/users/${URLEncoder.encode(idOrUpn, 'UTF-8').replace('+', '%20')}",
                ['$select': EntraUser.keys.join(',')]
            )
            def ret = data ? EntraUser.create(data) : null
            if (ret) {
                cacheUser(ret)
            } else {
                // Cache misses too - unmatched identifiers should not re-hit Graph per call.
                queryCache.put(key, NO_MATCH)
            }
            return ret
        }
    }

    /**
     * Lookup multiple users by object ID or userPrincipalName, in parallel.
     * @param idsOrUpns set of user object IDs or userPrincipalNames.
     * @param strictMode if true, this method will throw if any lookups fail, otherwise
     *      failed lookups will be logged and resolved as null. Identifiers that do not match a
     *      user resolve as null in either mode.
     */
    Map<String, EntraUser> lookupUsers(Set<String> idsOrUpns, boolean strictMode = false) {
        withDebug(["Looking up users", [ids: idsOrUpns, strictMode: strictMode]]) {
            parallelLookup(idsOrUpns) { String idOrUpn ->
                try {
                    lookupUser(idOrUpn)
                } catch (Exception e) {
                    if (strictMode) throw e
                    logError('Failure looking up user', [user: idOrUpn], e)
                    return null
                }
            }
        }
    }

    /**
     * Find users with the given field exactly equal to the given value. Supports reverse
     * lookups from identifiers other than the object ID / UPN - e.g. from a sAMAccountName or
     * SID (`onPremisesSamAccountName` / `onPremisesSecurityIdentifier`).
     *
     * @param field Graph user field to match - must be one of `EntraUser.queryKeys`. Boolean
     *      fields (e.g. `accountEnabled`) are not supported - this identifier-lookup API does
     *      not support tenant-wide sweeps on a flag.
     * @param value value to match exactly (OData `eq`).
     */
    List<EntraUser> findUsers(String field, String value) {
        withDebug(["Finding users", [field: field, value: value]]) {
            ensureEnabled()
            if (!(field in EntraUser.queryKeys)) {
                throw new RuntimeException("Invalid EntraUser field '$field' for findUsers - must be one of ${EntraUser.queryKeys}")
            }
            String key = "usersBy|$field|$value"
            def cached = queryCache.get(key)
            if (cached != null) return cached as List<EntraUser>

            // Filters on onPremises* fields are "advanced queries" - as with $search in
            // findGroups, they require the ConsistencyLevel header and $count param.
            // OData string literals escape embedded single quotes by doubling them.
            String escaped = value.replace("'", "''")
            List<Map> raw = graphGetList(
                '/users',
                [
                    '$select': EntraUser.keys.join(','),
                    '$filter': "$field eq '$escaped'".toString(),
                    '$count' : 'true'
                ],
                ['ConsistencyLevel': 'eventual']
            )
            def ret = raw.collect { EntraUser.create(it) }
            queryCache.put(key, ret)
            // Also seed the per-user entries, so a reverse lookup warms the forward cache.
            ret.each { cacheUser(it) }
            return ret
        }
    }

    /**
     * Find the single user with the given field exactly equal to the given value. Intended for
     * identity work where exactly one match is expected - e.g. resolving a user from an
     * on-prem SID.
     *
     * <p>Returns null when no user matches. Throws when multiple users match, rather than
     * silently pick one. A multi-match indicates an ambiguous identity that callers must not
     * bind to - e.g. colliding sAMAccountNames across synced domains.
     *
     * @param field String-typed Graph user field to match - see {@link #findUsers}.
     * @param value value to match exactly (OData `eq`).
     */
    EntraUser findUser(String field, String value) {
        List<EntraUser> matches = findUsers(field, value)
        if (matches.size() > 1) {
            throw new RuntimeException("Found ${matches.size()} users with $field = '$value' - refusing to resolve an ambiguous identity.")
        }
        matches ? matches.first() : null
    }

    //------------------------
    // Groups
    //------------------------
    /**
     * Lookup a single group by object ID.
     * @return matching group, or null if not found.
     */
    EntraGroup lookupGroup(String id) {
        withDebug(["Looking up group", [id: id]]) {
            lookupGroupInternal(id, true)
        }
    }

    /**
     * Lookup a number of groups by object ID, in parallel.
     * @param ids set of group object IDs.
     * @param strictMode if true, this method will throw if any lookups fail, otherwise
     *      failed lookups will be logged and resolved as null. Groups that do not exist
     *      resolve as null in either mode.
     */
    Map<String, EntraGroup> lookupGroups(Set<String> ids, boolean strictMode = false) {
        withDebug(["Looking up groups", [ids: ids, strictMode: strictMode]]) {
            parallelLookup(ids) { String id -> lookupGroupInternal(id, strictMode) }
        }
    }

    /**
     * Lookup all members of a single group, including members of any nested groups.
     * @param id group object ID.
     */
    List<EntraUser> lookupGroupMembers(String id) {
        withDebug(["Looking up group members", [id: id]]) {
            lookupGroupMembersInternal(id, true)
        }
    }

    /**
     * Lookup group members for a number of groups, in parallel. Graph resolves nested group
     * memberships server-side and includes them.
     * @param ids set of group object IDs.
     * @param strictMode if true, this method will throw if any lookups fail, otherwise
     *      failed lookups will be logged and resolved as null.
     */
    Map<String, List<EntraUser>> lookupGroupMembers(Set<String> ids, boolean strictMode = false) {
        withDebug(["Looking up group members", [ids: ids, strictMode: strictMode]]) {
            parallelLookup(ids) { String id -> lookupGroupMembersInternal(id, strictMode) }
        }
    }

    /**
     * Find all groups with a display name matching the given term. Matching is tokenized - each
     * word or separator-delimited segment of the display name matches by prefix, so e.g. 'admin'
     * matches both 'toolbox-admin' and 'Admin Users'. Graph does not support mid-token
     * (contains) matching.
     */
    List<EntraGroup> findGroups(String namePart) {
        withDebug("Finding groups with name matching $namePart") {
            ensureEnabled()
            // $search is an "advanced query" - requires the ConsistencyLevel header, and reads
            // an eventually-consistent index, so a just-created group may lag briefly.
            // Embedded double quotes and backslashes escape with a backslash.
            String escaped = namePart.replace('\\', '\\\\').replace('"', '\\"')
            List<Map> raw = graphGetList(
                '/groups',
                [
                    '$select': EntraGroup.keys.join(','),
                    '$search': "\"displayName:$escaped\"".toString(),
                    '$count' : 'true'
                ],
                ['ConsistencyLevel': 'eventual']
            )
            raw.collect { EntraGroup.create(it) }
        }
    }

    //------------------------
    // DirectoryService
    //------------------------
    String getDirectoryGroupsDescription() {
        'Search by name, or enter a group object ID (GUID) directly.'
    }

    Map<String, ErrorOr<Set<String>>> loadUsersForDirectoryGroups(Set<String> groups, boolean strictMode) {
        ensureEnabled()
        if (!groups) return emptyMap()

        def conf = config
        String userAttr = conf.usernameAttribute
        boolean stripDomain = conf.stripUsernameDomain
        if (!(userAttr in EntraUser.usernameKeys)) {
            def msg = "Invalid xhEntraIdConfig.usernameAttribute '$userAttr' - must be one of ${EntraUser.usernameKeys}"
            if (strictMode) throw new RuntimeException(msg)
            logError(msg)
            return groups.collectEntries { [it, ErrorOr.error(msg)] }
        }

        parallelLookup(groups) { String id -> loadUsersForGroupInternal(id, userAttr, stripDomain, strictMode) }
    }

    Map<String, ErrorOr<Map>> describeDirectoryGroups(Set<String> groups) {
        ensureEnabled()
        lookupGroups(groups, false).collectEntries { id, group ->
            [id, group ? ErrorOr.of(group.formatForJSON()) : ErrorOr.error('Directory Group not found')]
        } as Map<String, ErrorOr<Map>>
    }

    List<Map> searchDirectoryGroups(String namePart) {
        ensureEnabled()
        findGroups(namePart).collect { it.formatForJSON() }
    }

    //------------------------
    // Admin stats
    //------------------------
    Map getAdminStats() {[
        config: configForAdminStats('xhEntraIdConfig', 'xhEntraTenantId', 'xhEntraClientId'),
        enabled: enabled,
        authMode: authMode,
        certificate: certificateAdminStats
    ]}

    List<String> getComparableAdminStats() { ['enabled', 'authMode'] }

    void clearCaches() {
        queryCache.clear()
        synchronized (this) {
            _msalClient = null
        }
        super.clearCaches()
    }

    //------------------------
    // Implementation
    //------------------------
    private EntraGroup lookupGroupInternal(String id, boolean strictMode) {
        try {
            ensureEnabled()
            validateGroupId(id)

            String key = "group|$id"
            def cached = queryCache.get(key)
            if (cached != null) return cached as EntraGroup

            def data = graphGetObject("/groups/$id", ['$select': EntraGroup.keys.join(',')])
            def ret = data ? EntraGroup.create(data) : null
            if (ret) queryCache.put(key, ret)
            return ret
        } catch (Exception e) {
            if (strictMode) throw e
            logError('Failure looking up group', [id: id], e)
            return null
        }
    }

    private List<EntraUser> lookupGroupMembersInternal(String id, boolean strictMode) {
        try {
            ensureEnabled()
            validateGroupId(id)

            String key = "groupMembers|$id"
            def cached = queryCache.get(key)
            if (cached != null) return cached as List<EntraUser>

            // The `/microsoft.graph.user` OData cast makes this an "advanced query", which
            // requires the ConsistencyLevel header and $count param. Note advanced queries read
            // an eventually-consistent index - very recent membership changes may lag briefly.
            def raw = graphGetList(
                "/groups/$id/transitiveMembers/microsoft.graph.user",
                ['$select': EntraUser.keys.join(','), '$top': '999', '$count': 'true'],
                ['ConsistencyLevel': 'eventual']
            )
            def ret = raw.collect { EntraUser.create(it) }
            queryCache.put(key, ret)
            return ret
        } catch (Exception e) {
            if (strictMode) throw e
            logError('Failure looking up group members', [id: id], e)
            return null
        }
    }

    /**
     * Resolve a single directory group to member usernames for the role management flow.
     * Returns a successful ErrorOr with member usernames, or a failed one when the group does
     * not exist or (in non-strict mode) when the lookup fails.
     */
    private ErrorOr<Set<String>> loadUsersForGroupInternal(String id, String userAttr, boolean stripDomain, boolean strictMode) {
        try {
            List<EntraUser> members = lookupGroupMembersInternal(id, true)
            Set<String> usernames = members.collect {
                String username = it[userAttr]?.toString()?.toLowerCase()
                stripDomain && username ? username.split('@', 2)[0] : username
            } as Set
            // Exclude members without a value for the configured username attribute.
            usernames.remove(null)
            return ErrorOr.of(usernames)
        } catch (Exception e) {
            // Group-not-found is reported as data, not thrown - matching the LDAP-based flow,
            // where admins can see and correct a bad group ID stored on a role.
            if (isNotFound(e)) return ErrorOr.error('Directory Group not found')
            if (strictMode) throw e
            logError('Error resolving users for directory group', [id: id], e)
            return ErrorOr.error(e.message ?: 'Error resolving directory group')
        }
    }

    /** GET a single Graph object. Returns the parsed response Map, or null on a 404. */
    private Map graphGetObject(String path, Map<String, String> params) {
        try {
            executeGraphGet(buildUrl(path, params))
        } catch (Exception e) {
            if (isNotFound(e)) return null
            throw e
        }
    }

    /** GET a Graph collection, following `@odata.nextLink` paging until exhausted. */
    private List<Map> graphGetList(String path, Map<String, String> params, Map<String, String> headers = [:]) {
        List<Map> ret = []
        String url = buildUrl(path, params)
        while (url) {
            Map page = executeGraphGet(url, headers)
            def value = page.value
            if (value instanceof List) ret.addAll(value as List<Map>)
            url = page['@odata.nextLink']
        }
        return ret
    }

    private String buildUrl(String path, Map<String, String> params) {
        def builder = new URIBuilder(GRAPH_BASE_URL + path)
        params.each { k, v -> builder.addParameter(k, v) }
        builder.build().toString()
    }

    private Map executeGraphGet(String url, Map<String, String> headers = [:]) {
        int attempt = 0
        while (true) {
            try {
                return withTrace(['Querying Graph', [url: url]]) {
                    def get = new HttpGet(url)
                    get.setHeader('Authorization', "Bearer ${acquireAccessToken()}")
                    headers.each { k, v -> get.setHeader(k, v) }
                    jsonClient.executeAsMap(get, config.timeoutMs)
                }
            } catch (HttpException e) {
                // Bounded retry on throttling (429) and server errors, with linear backoff.
                def status = e.statusCode
                if ((status == 429 || status >= 500) && attempt++ < MAX_RETRIES) {
                    logWarn('Graph request failed - will retry', [url: url, status: status, attempt: attempt])
                    sleep(RETRY_DELAY_MS * attempt)
                    continue
                }
                throw e
            }
        }
    }

    private String acquireAccessToken() {
        // msal4j caches tokens in-memory and renews on demand - safe to call per-request.
        def params = ClientCredentialParameters.builder(GRAPH_SCOPES).build()
        msalClient.acquireToken(params).get().accessToken()
    }

    private synchronized IConfidentialClientApplication getMsalClient() {
        if (!_msalClient) {
            String tenantId = getTenantId(),
                clientId = configService.getStringIfSet('xhEntraClientId')
            if (!tenantId || !clientId) {
                throw new RuntimeException('EntraIdService enabled but tenant/client not configured - check xhEntraTenantId and xhEntraClientId app configs.')
            }
            IClientCredential credential = EntraClientCredentials.create(
                configService.getPwdIfSet('xhEntraClientPfx'),
                configService.getPwdIfSet('xhEntraClientPfxPassword'),
                configService.getPwdIfSet('xhEntraClientSecret')
            )
            _msalClient = ConfidentialClientApplication
                .builder(clientId, credential)
                .authority("https://login.microsoftonline.com/${tenantId}")
                .build()
        }
        return _msalClient
    }

    /** Credential type in use - `certificate` when one is configured, `clientSecret` otherwise. */
    private String getAuthMode() {
        configService.getPwdIfSet('xhEntraClientPfx') ? 'certificate' : 'clientSecret'
    }

    /**
     * Identifying details of the configured certificate (public material only) for the Admin
     * Console - most usefully its expiry date and its thumbprint, which matches the certificate
     * list on the Entra ID app registration. Null when running on a client secret.
     */
    private Map getCertificateAdminStats() {
        String pfx = configService.getPwdIfSet('xhEntraClientPfx')
        if (!pfx) return null
        try {
            X509Certificate cert = EntraClientCredentials.parseCertificate(
                pfx, configService.getPwdIfSet('xhEntraClientPfxPassword')
            )
            return [
                subject: cert.subjectX500Principal.name,
                // SHA-1 hex, matching the "Thumbprint" column shown in the Azure portal.
                thumbprint: MessageDigest.getInstance('SHA-1').digest(cert.encoded).encodeHex().toString().toUpperCase(),
                notAfter: cert.notAfter
            ]
        } catch (Exception e) {
            return [error: e.message]
        }
    }

    private String getTenantId() {
        configService.getStringIfSet('xhEntraTenantId')
    }

    private synchronized JSONClient getJsonClient() {
        _jsonClient ?= new JSONClient()
    }

    /**
     * Run a per-key lookup with bounded parallelism - batches of up to MAX_PARALLEL_LOOKUPS
     * keys run concurrently, with each batch awaited in full before the next begins. The bound
     * is sized to run typical workloads in a single fully-parallel batch. It is also a backstop
     * against unbounded thread and connection fan-out from very large key sets.
     */
    private <T> Map<String, T> parallelLookup(Set<String> keys, Closure<T> lookupFn) {
        Map<String, T> ret = [:]
        keys.toList().collate(MAX_PARALLEL_LOOKUPS).each { batch ->
            Map<String, Promise<T>> tasks =
                batch.collectEntries { String key -> [key, task { lookupFn(key) }] }
            tasks.each { k, v -> ret.put(k, v.get()) }
        }
        ret
    }

    /** Cache a fetched user under both its identifiers, so id- and UPN-based lookups share an entry. */
    private void cacheUser(EntraUser user) {
        String idKey = "user|${user.id.toLowerCase()}"
        queryCache.put(idKey, user)
        if (user.userPrincipalName) {
            String upnKey = "user|${user.userPrincipalName.toLowerCase()}"
            queryCache.put(upnKey, user)
        }
    }

    private void ensureEnabled() {
        if (!enabled) throw new RuntimeException('EntraIdService not enabled - check xhEntraIdConfig app config.')
    }

    private void validateGroupId(String id) {
        if (!id || !GUID_PATTERN.matcher(id).matches()) {
            throw new RuntimeException("Invalid Entra ID group identifier '$id' - expected the group's object ID (GUID).")
        }
    }

    private boolean isNotFound(Exception e) {
        e instanceof HttpException && e.statusCode == 404
    }

    private EntraIdConfig getConfig() {
        configService.getObject(EntraIdConfig)
    }
}
