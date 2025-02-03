package io.xh.hoist.ldap

import io.xh.hoist.BaseService
import io.xh.hoist.cache.Cache
import org.apache.directory.api.ldap.model.entry.Attribute
import org.apache.directory.api.ldap.model.message.SearchScope
import org.apache.directory.api.ldap.model.exception.LdapAuthenticationException
import org.apache.directory.ldap.client.api.LdapConnectionConfig
import org.apache.directory.ldap.client.api.NoVerificationTrustManager
import org.apache.directory.ldap.client.api.LdapNetworkConnection

import static grails.async.Promises.task
import static io.xh.hoist.util.DateTimeUtils.SECONDS

/**
 * Service to query a set of LDAP servers for People, Groups, and Group memberships.
 *
 * Requires the following application configs:
 *      - 'xhLdapConfig' with the following options
 *          - enabled - true to enable
 *          - timeoutMs - time to wait for any individual search to resolve.
 *          - cacheExpireSecs - length of time to cache results.  Set to -1 to disable caching.
 *          - skipTlsCertVerification - true to accept untrusted certificates when binding
 *          - useMatchingRuleInChain - true to use Microsoft Active Directory's proprietary
 *           "LDAP_MATCHING_RULE_IN_CHAIN" rule (the magic `1.2.840.113556.1.4.1941` string below).
 *           This can be a more efficient way to resolve users in nested groups but should be used
 *           with caution, as it can trigger a large database walk, which has a significant
 *           performance impact that is unnecessary when queries are not expected to return deeply
 *           nested groups.
*           - servers - list of servers to be queried, each containing:
*              - host
*              - baseUserDn
*              - baseGroupDn
 *     - 'xhLdapUsername' - dn of query user.
 *     - 'xhLdapPassword' - password for user
 *
 * This service will cache results, per server, for the configured interval.
 * This service may return partial results if any particular server fails to return results.
 */
class LdapService extends BaseService {

    def configService

    private Cache<String, List<LdapObject>> cache = createCache(
        name: 'queryCache',
        expireTime: {config.cacheExpireSecs * SECONDS}
    )

    static clearCachesConfigs = ['xhLdapConfig', 'xhLdapUsername', 'xhLdapPassword']

    boolean getEnabled() {
        config.enabled
    }

    LdapPerson lookupUser(String sName) {
        searchOne("(sAMAccountName=$sName) ", LdapPerson, true)
    }

    List<LdapPerson> lookupGroupMembers(String dn) {
        lookupGroupMembersInternal(dn, true)
    }

    List<LdapGroup> findGroups(String sNamePart) {
        searchMany("(sAMAccountName=*$sNamePart*)", LdapGroup, true)
    }

    /**
     * Lookup a number of groups in parallel.
     * @param dns - set of distinguished names.
     * @param strictMode - if true, this method will throw if any lookups fail,
     *      otherwise, failed lookups will be logged, and resolved as null.
     */
    Map<String, LdapGroup> lookupGroups(Set<String> dns, boolean strictMode = false) {
        dns.collectEntries { dn -> [dn, task { lookupGroupInternal(dn, strictMode) }] }
            .collectEntries { [it.key, it.value.get()] }
    }

    /**
     * Lookup group members for a number of groups in parallel.
     * @param dns - set of distinguished names.
     * @param strictMode - if true, this method will throw if any lookups fail,
     *      otherwise, failed lookups will be logged, and resolved as an empty list.
     */
    Map<String, List<LdapPerson>> lookupGroupMembers(Set<String> dns, boolean strictMode = false) {
        dns.collectEntries { dn -> [dn, task { lookupGroupMembersInternal(dn, strictMode) }] }
            .collectEntries { [it.key, it.value.get()] }
    }

    /**
     * Search for a single object, returning the first match found.
     * @param baseFilter - an LDAP filter to be appended to the objectCategory filter.
     * @param objType - type of Hoist-Core LdapObject to search for - must be or extend LdapObject, LdapPerson, or LdapGroup
     * @param strictMode - if true, this method will throw if any lookups fail
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
     * @param baseFilter - an LDAP filter to be appended to the objectCategory filter.
     * @param objType - type of Hoist-Core LdapObject to search for - must be or extend LdapObject, LdapPerson, or LdapGroup
     * @param strictMode - if true, this method will throw if any lookups fail
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
     * @param username - sAMAccountName for user
     * @param password - credentials for user
     * @return true if the password is valid and the test connection succeeds
     */
    boolean authenticate(String username, String password) {
        for (Map server in config.servers) {
            String host = server.host
            List<LdapPerson> matches = doQuery(server, "(sAMAccountName=$username)", LdapPerson, true)
            if (matches) {
                if (matches.size() > 1) throw new RuntimeException("Multiple user records found for $username")
                LdapPerson user = matches.first()
                try (def conn = createConnection(host)) {
                    conn.bind(user.distinguishedname, password)
                    conn.unBind()
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

    //----------------------
    // Implementation
    //----------------------
    private LdapGroup lookupGroupInternal(String dn, boolean strictMode) {
        searchOne("(distinguishedName=$dn)", LdapGroup, strictMode)
    }

    private List<LdapPerson> lookupGroupMembersInternal(String dn, boolean strictMode) {
        config.useMatchingRuleInChain ?
            // See class-level comment regarding this AD-specific query
            searchMany("(|(memberOf=$dn) (memberOf:1.2.840.113556.1.4.1941:=$dn))", LdapPerson, strictMode) :
            lookupGroupMembersRecursive(dn, strictMode)
    }

    private List<LdapPerson> lookupGroupMembersRecursive(
        String dn,
        boolean strictMode,
        Set<String> visitedGroups = new HashSet<String>()
    ) {
        if (!visitedGroups.add(dn)) return []
        List<LdapPerson> users = searchMany("(memberOf=$dn)", LdapPerson, strictMode)
        List<LdapGroup> groups = searchMany("(memberOf=$dn)", LdapGroup, strictMode)
        groups.each { LdapGroup group ->
            users.addAll(lookupGroupMembersRecursive(group.distinguishedname, strictMode, visitedGroups))
        }
        return users
    }

    private <T extends LdapObject> List<T> doQuery(Map server, String baseFilter, Class<T> objType, boolean strictMode) {
        if (!enabled) throw new RuntimeException('LdapService not enabled - check xhLdapConfig app config.')
        if (queryUsername == 'none') throw new RuntimeException('LdapService enabled but query user not configured - check xhLdapUsername app config, or disable via xhLdapConfig.')

        boolean isPerson = LdapPerson.class.isAssignableFrom(objType)
        String host = server.host,
            filter = "(&(objectCategory=${isPerson ? 'Person' : 'Group'})$baseFilter)",
            key = server.toString() + filter

        List<T> ret = cache.get(key)
        if (ret != null) return ret

        withDebug(["Querying LDAP", [host: host, filter: filter]]) {
            try (def conn = createConnection(host)) {
                String baseDn = isPerson ? server.baseUserDn : server.baseGroupDn
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
        ret.timeout = config.timeoutMs as Long
        ret.useTls = true

        if (config.skipTlsCertVerification) {
            ret.setTrustManagers(new NoVerificationTrustManager())
        }

        return new LdapNetworkConnection(ret)
    }

    private Map getConfig() {
        configService.getMap('xhLdapConfig')
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
