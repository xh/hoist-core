package io.xh.hoist.ldap

import io.xh.hoist.BaseService
import io.xh.hoist.cache.Cache
import org.apache.directory.api.ldap.model.entry.Attribute
import org.apache.directory.api.ldap.model.message.SearchScope
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
 *          - servers - list of servers to be queried, each containing:
 *              - host
 *              - baseUserDn
 *              - baseGroupDn
 *     - 'xhLdapUsername' - dn of query user.
 *     - 'xhLdapPassword' - password for user
 *
 * This service will cache results, per server, for the configured interval.
 * This service may return partial results if any particular server fails to return results.
 *
 * Note that the implementation of `lookupGroupMembers()` is currently specific to Microsoft Active
 * Directory, due to the use of the proprietary "LDAP_MATCHING_RULE_IN_CHAIN" rule OID (the magic
 * `1.2.840.113556.1.4.1941` string below). This is an efficient way to resolve users in nested
 * groups, but would require an alternate implementation if this service is required to work with
 * more generic LDAP deployments.
 */
class LdapService extends BaseService {

    def configService

    private Cache<String, List<LdapObject>> cache = new Cache<>(
        expireTime: {config.cacheExpireSecs * SECONDS},
        svc: this
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

    //----------------------
    // Implementation
    //----------------------
    private LdapGroup lookupGroupInternal(String dn, boolean strictMode) {
        searchOne("(distinguishedName=$dn)", LdapGroup, strictMode)
    }

    private List<LdapPerson> lookupGroupMembersInternal(String dn, boolean strictMode) {
        // See class-level comment regarding this AD-specific query
        searchMany("(|(memberOf=$dn) (memberOf:1.2.840.113556.1.4.1941:=$dn))", LdapPerson, strictMode)
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
            try (LdapNetworkConnection conn = new LdapNetworkConnection(host)) {
                String baseDn = isPerson ? server.baseUserDn : server.baseGroupDn
                String[] keys = objType.keys.toArray() as String[]

                conn.timeOut = config.timeoutMs as Long

                boolean didBind = false
                try {
                    conn.bind(queryUsername, queryUserPwd)
                    didBind = true
                    ret = conn.search(baseDn, filter, SearchScope.SUBTREE, keys)
                        .collect { objType.create(it.attributes as Collection<Attribute>) }
                    cache.put(key, ret)
                } finally {
                    // Calling unBind on an unbound connection will throw an exception
                    if (didBind) conn.unBind()
                }
            } catch (Exception e) {
                if (strictMode) throw e
                logError("Failure querying", [host: host, filter: filter], e)
                ret = null
            }
        }
        return ret
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
