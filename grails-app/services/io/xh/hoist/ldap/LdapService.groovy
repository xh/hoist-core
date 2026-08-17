/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.ldap

import grails.async.Promise
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.cache.Cache
import io.xh.hoist.config.ConfigService
import io.xh.hoist.directory.DirectoryService
import io.xh.hoist.util.ErrorOr
import org.apache.directory.api.ldap.model.entry.Attribute
import org.apache.directory.api.ldap.model.exception.LdapAuthenticationException
import org.apache.directory.api.ldap.model.message.SearchScope
import org.apache.directory.ldap.client.api.LdapConnectionConfig
import org.apache.directory.ldap.client.api.LdapNetworkConnection
import org.apache.directory.ldap.client.api.NoVerificationTrustManager

import static grails.async.Promises.task
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static java.util.Collections.emptyMap

/**
 * Service to query a set of LDAP servers for People, Groups, and Group memberships.
 *
 * Requires the following soft configs:
 * <ul>
 *   <li>`xhLdapConfig` - connection, caching, and per-server search settings.
 *       See {@link LdapConfig} for all supported options and their defaults.</li>
 *   <li>`xhLdapUsername` - dn of the user to bind as when querying.</li>
 *   <li>`xhLdapPassword` - password for the query user.</li>
 * </ul>
 *
 * <p>Servers are queried in the order configured, with results cached per server and filter for
 * `xhLdapConfig.cacheExpireSecs`. Queries run with `strictMode = false` will log and skip any
 * server that fails to respond, meaning callers can receive partial results.
 */
@CompileStatic
class LdapService extends BaseService implements DirectoryService {

    ConfigService configService

    /** Max lookups run concurrently by {@link #parallelLookup} - see that method for context. */
    private static final int MAX_PARALLEL_LOOKUPS = 25

    private Cache<String, List<LdapObject>> cache = createCache(
        name: 'queryCache',
        expireTime: {config.cacheExpireSecs * SECONDS}
    )

    static clearCachesConfigs = ['xhLdapConfig', 'xhLdapUsername', 'xhLdapPassword']

    /**
     * True if configured for use - all query methods below will throw if called when false.
     */
    boolean getEnabled() {
        config.enabled
    }

    /**
     * Lookup a single user by account name, returning the first match across all servers.
     * @param sName sAMAccountName for user.
     * @return matching user, or null if not found.
     */
    LdapPerson lookupUser(String sName) {
        withDebug(["Looking up user", [sAMAccountName: sName]]) {
            searchOne("(sAMAccountName=$sName) ", LdapPerson, true)
        }
    }

    /**
     * Lookup all members of a single group, including members of any nested groups.
     * @param dn distinguished name of the group.
     */
    List<LdapPerson> lookupGroupMembers(String dn) {
        withDebug(["Looking up group members", [dn: dn]]) {
            lookupGroupMembersInternal(dn, true)
        }
    }

    /**
     * Find all groups with an account name containing the given substring.
     * @param sNamePart partial sAMAccountName, matched with leading and trailing wildcards.
     */
    List<LdapGroup> findGroups(String sNamePart) {
        withDebug("Finding groups with name matching *$sNamePart") {
            searchMany("(sAMAccountName=*$sNamePart*)", LdapGroup, true)
        }
    }

    /**
     * Lookup a number of groups in parallel.
     * @param dns set of distinguished names.
     * @param strictMode if true, this method will throw if any lookups fail,
     *      otherwise, failed lookups will be logged, and resolved as null.
     */
    Map<String, LdapGroup> lookupGroups(Set<String> dns, boolean strictMode = false) {
        withDebug(["Looking up groups", [dns: dns, strictMode: strictMode]]) {
            parallelLookup(dns) { String dn -> lookupGroupInternal(dn, strictMode) }
        }
    }

    /**
     * Lookup group members for a number of groups in parallel.
     * @param dns set of distinguished names.
     * @param strictMode if true, this method will throw if any lookups fail,
     *      otherwise, failed lookups will be logged, and resolved as an empty list.
     */
    Map<String, List<LdapPerson>> lookupGroupMembers(Set<String> dns, boolean strictMode = false) {
        withDebug(["Looking up group members", [dns: dns, strictMode: strictMode]]) {
            parallelLookup(dns) { String dn -> lookupGroupMembersInternal(dn, strictMode) }
        }
    }

    /**
     * Search for a single object, returning the first match found.
     * @param baseFilter an LDAP filter to be appended to the objectCategory filter.
     * @param objType type of Hoist-Core LdapObject to search for - must be or extend LdapObject, LdapPerson, or LdapGroup
     * @param strictMode if true, this method will throw if any lookups fail
     * @return first match found in the form of objType
     */
    <T extends LdapObject> T searchOne(String baseFilter, Class<T> objType, boolean strictMode) {
        for (server in config.servers) {
            List<T> matches = doQuery(server, baseFilter, objType, strictMode)
            if (matches) return matches.first()
        }
        return null
    }

    /**
     * Search for multiple objects, returning all matches found.
     * @param baseFilter an LDAP filter to be appended to the objectCategory filter.
     * @param objType type of Hoist-Core LdapObject to search for - must be or extend LdapObject, LdapPerson, or LdapGroup
     * @param strictMode if true, this method will throw if any lookups fail
     * @return list of all matches found in the form of objType
     */
    <T extends LdapObject> List<T> searchMany(String baseFilter, Class<T> objType, boolean strictMode) {
        List<T> ret = []
        for (server in config.servers) {
            List<T> matches = doQuery(server, baseFilter, objType, strictMode)
            if (matches) ret.addAll(matches)
        }
        return ret
    }

    /**
     * Validate a domain user's password by confirming it can be used to bind to a configured LDAP
     * server. Note this does *not* on its own cause the user to become authenticated to this
     * application - it is intended to support an alternate form-based login strategy as a backup
     * to primary OAuth/SSO authentication.
     *
     * @param username sAMAccountName for user
     * @param password credentials for user
     * @return true if the password is valid and the test connection succeeds
     */
    boolean authenticate(String username, String password) {
        withDebug(["Attempting LDAP bind to authenticate user", [username: username]]) {
            for (server in config.servers) {
                String host = server.host
                List<LdapPerson> matches = doQuery(server, "(sAMAccountName=$username)", LdapPerson, true)
                if (matches) {
                    if (matches.size() > 1) throw new RuntimeException("Multiple user records found for $username")
                    LdapPerson user = matches.first()
                    try (def conn = createConnection(host)) {
                        conn.bind(user.distinguishedname, password)
                        conn.unBind()
                        logDebug('Authentication successful', [username: username])
                        return true
                    } catch (LdapAuthenticationException ignored) {
                        logDebug('Authentication failed, incorrect credentials', [username: username])
                        return false
                    }
                }
            }
            logDebug('Authentication failed, no user found', [username: username])
            return false
        }
    }

    //------------------------
    // DirectoryService
    //------------------------
    String getDirectoryGroupsDescription() {
        'Specify the full LDAP Distinguished Name (DN) for the directory group to be included.'
    }

    /**
     * Usernames are the members' `xhLdapConfig.usernameAttribute` values, lowercased. The
     * `samaccountname` default is the long-standing convention for LDAP-backed role resolution.
     */
    Map<String, ErrorOr<Set<String>>> loadUsersForDirectoryGroups(Set<String> groups, boolean strictMode) {
        ensureEnabled()
        if (!groups) return emptyMap()

        String userAttr = config.usernameAttribute
        if (!(userAttr in LdapPerson.keys)) {
            def msg = "Invalid xhLdapConfig.usernameAttribute '$userAttr' - must be one of ${LdapPerson.keys}"
            if (strictMode) throw new RuntimeException(msg)
            logError(msg)
            return groups.collectEntries { [it, ErrorOr.error(msg)] }
        }

        Set<String> foundGroups = new HashSet()
        Map<String, ErrorOr<Set<String>>> ret = [:]

        // 1) Determine valid groups
        lookupGroups(groups, strictMode).each { name, group ->
            if (group) {
                foundGroups << name
            } else {
                ret[name] = ErrorOr.error('Directory Group not found')
            }
        }

        // 2) Search for members of valid groups
        lookupGroupMembers(foundGroups, strictMode).each { name, members ->
            Set<String> users = members.collect(new HashSet()) { it[userAttr]?.toString()?.toLowerCase() }
            // Exclude members without the username attribute (e.g. email-only contacts in a DL)
            users.remove(null)
            ret[name] = ErrorOr.of(users)
        }

        return ret
    }

    Map<String, ErrorOr<Map>> describeDirectoryGroups(Set<String> groups) {
        ensureEnabled()
        lookupGroups(groups, false).collectEntries { dn, group ->
            [dn, group ?
                ErrorOr.of([id: dn, displayName: group.cn ?: group.name ?: dn]) :
                ErrorOr.error('Directory Group not found')
            ]
        } as Map<String, ErrorOr<Map>>
    }

    List<Map> searchDirectoryGroups(String namePart) {
        ensureEnabled()
        findGroups(namePart).collect {
            [id: it.distinguishedname, displayName: it.cn ?: it.name ?: it.distinguishedname] as Map
        }
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhLdapConfig', 'xhLdapUsername'),
        enabled: enabled
    ]}

    List<String> getComparableAdminStats() { ['enabled'] }

    //----------------------
    // Implementation
    //----------------------
    private LdapGroup lookupGroupInternal(String dn, boolean strictMode) {
        searchOne("(distinguishedName=$dn)", LdapGroup, strictMode)
    }

    private List<LdapPerson> lookupGroupMembersInternal(String dn, boolean strictMode) {
        // See LdapConfig.useMatchingRuleInChain regarding this AD-specific query
        config.useMatchingRuleInChain ?
            searchMany("(|(memberOf=$dn) (memberOf:1.2.840.113556.1.4.1941:=$dn))", LdapPerson, strictMode) :
            lookupMembersRecursive(dn, strictMode).values().asList()
    }

    private Map<String, LdapPerson> lookupMembersRecursive(
        String dn,
        boolean strictMode,
        Map<String, LdapPerson> members = new HashMap(),
        Set<String> visited = new HashSet<String>()
    ) {
        if (visited.add(dn)) {
            // Add direct users
            searchMany("(memberOf=$dn)", LdapPerson, strictMode)
                .each { members[it.distinguishedname] = it}

            // Recursively add nested groups
            searchMany("(memberOf=$dn)", LdapGroup, strictMode)
                .each {lookupMembersRecursive(it.distinguishedname, strictMode, members, visited)}
        }
        return members
    }

    // CompileDynamic to support the polymorphic static dispatch of objType.keys / objType.create,
    // which resolves to the runtime Class - including app-defined LdapObject subclasses.
    @CompileDynamic
    private <T extends LdapObject> List<T> doQuery(LdapConfig.LdapServerOptions server, String baseFilter, Class<T> objType, boolean strictMode) {
        ensureEnabled()
        if (queryUsername == 'none') throw new RuntimeException('LdapService enabled but query user not configured - check xhLdapUsername app config, or disable via xhLdapConfig.')

        boolean isPerson = LdapPerson.class.isAssignableFrom(objType)
        // Cache key MUST include `baseDn` alongside `host` and `filter`. Apps commonly
        // configure multiple `servers` entries that share a `host` but search different
        // `baseUserDn` / `baseGroupDn` subtrees; if `baseDn` is dropped from the key, the
        // first server's (possibly empty) result short-circuits every subsequent server
        // for the same filter and entries under the other base DNs become invisible.
        // See PR #336 for the original fix; PR #545 silently regressed this when the
        // server param was retyped and the key was simplified to `host + filter`.
        String host = server.host,
            baseDn = isPerson ? server.baseUserDn : server.baseGroupDn,
            filter = "(&(objectCategory=${isPerson ? 'Person' : 'Group'})$baseFilter)",
            key = "$host|$baseDn|$filter"

        List<T> ret = cache.get(key)
        if (ret != null) return ret

        withTrace(["Querying LDAP", [host: host, filter: filter]]) {
            try (def conn = createConnection(host)) {
                String[] keys = objType.keys.toArray() as String[]
                boolean didBind = false
                try {
                    conn.bind(queryUsername, queryUserPwd)
                    didBind = true
                    ret = conn.search(baseDn, filter, SearchScope.SUBTREE, keys)
                        .collect { objType.create(it.attributes as Collection<Attribute>) }
                    cache.put(key, ret)
                } finally {
                    if (didBind) conn.unBind()  // If unbound will throw an exception
                }
            } catch (Exception e) {
                if (strictMode) throw e
                logError("Failure querying", [host: host, filter: filter], e)
                ret = null
            }
        }
        return ret
    }

    private LdapNetworkConnection createConnection(String host) {
        def ret = new LdapConnectionConfig()
        ret.ldapHost = host
        ret.ldapPort = ret.defaultLdapPort
        ret.timeout = config.timeoutMs
        ret.useTls = true

        if (config.skipTlsCertVerification) {
            ret.setTrustManagers(new NoVerificationTrustManager())
        }

        return new LdapNetworkConnection(ret)
    }

    /**
     * Run a per-key lookup with bounded parallelism - batches of up to MAX_PARALLEL_LOOKUPS
     * keys run concurrently, with each batch awaited in full before the next begins. The bound
     * is sized to run typical workloads in a single fully-parallel batch, while acting as a
     * backstop against unbounded thread and connection fan-out from very large key sets.
     */
    private <T> Map<String, T> parallelLookup(Set<String> keys, Closure<T> lookupFn) {
        Map<String, T> ret = [:]
        keys.toList().collate(MAX_PARALLEL_LOOKUPS).each { batch ->
            Map<String, Promise<T>> tasks =
                batch.collectEntries { String key -> [key, task { lookupFn(key) }] }
            tasks.each { k, v -> ret[k] = v.get() }
        }
        ret
    }

    private void ensureEnabled() {
        if (!enabled) throw new RuntimeException('LdapService not enabled - check xhLdapConfig app config.')
    }

    private LdapConfig getConfig() {
        configService.getObject(LdapConfig)
    }

    private String getQueryUsername() {
        configService.getString('xhLdapUsername')
    }

    private String getQueryUserPwd() {
        configService.getPwd('xhLdapPassword')
    }

    void clearCaches() {
        cache.clear()
        super.clearCaches()
    }
}
