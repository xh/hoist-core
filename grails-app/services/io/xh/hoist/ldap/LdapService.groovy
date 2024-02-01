package io.xh.hoist.ldap

import io.xh.hoist.BaseService
import org.apache.directory.api.ldap.model.entry.Attribute
import org.apache.directory.api.ldap.model.message.SearchScope
import org.apache.directory.ldap.client.api.LdapNetworkConnection
import io.xh.hoist.cache.Cache
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static grails.async.Promises.task


/**
 * Query a set of LDAP servers for People or Groups.
 *
 * Requires the following configs
 *      'xhLdapConfig' with the following options
 *          enabled - true to enable
 *          timeoutMs - time to wait for any individual search to resolve.
 *          cacheExpireSecs - length of time to cache results.  Set to -1 to disable caching.
 *          servers - list of servers to be queried, each containing:
 *              host
 *              baseUserDn
 *              baseGroupDn
 *
 *     'xhLdapUsername' - dn of query user.
 *     'xhLdapPassword' - password for user
 *
 * This service will cache results, per server, for the configured interval.
 * This service may return partial results if any particular server fails to
 * return results.
 */
class LdapService extends BaseService {

    def configService

    private Cache<String, List<LdapObject>> cache

    static clearCachesConfigs = ['xhLdapConfig', 'xhLdapUsername', 'xhLdapPassword']

    void init() {
        initCache()
    }

    boolean getEnabled() {
        config.enabled
    }


    LdapPerson lookupUser(String sName) {
        searchOne("(sAMAccountName=$sName) ", LdapPerson)
    }

    List<LdapPerson> lookupGroupMembers(String dn) {
        searchMany("(|(member0f=$dn) (memberOf:1.2.840.113556.1.4.1941:=$dn))", LdapPerson)
    }

    Map<String, List<LdapPerson>> lookupGroupMembers(Set<String> dns) {
        dns.collectEntries { dn -> [dn, task { lookupGroupMembers(dn) }] }
            .collectEntries { [it.key, it.value.get()] }
    }

    Map<String, LdapGroup> lookupGroups(Set<String> dns) {
        dns.collectEntries { dn -> [dn, task { lookupGroup(dn) }] }
            .collectEntries { [it.key, it.value.get()] }
    }

    List<LdapGroup> findGroups(String sNamePart) {
        searchMany("(sAMAccountName=*$sNamePart*)", LdapGroup)
    }

    //----------------------
    // Implementation
    //----------------------
    private LdapGroup lookupGroup(String dn) {
        searchOne("(distinguishedName=$dn)", LdapGroup)
    }

    private <T extends LdapObject> T searchOne(String baseFilter, Class<T> objType) {
        for (server in config.servers) {
            List<T> matches = doQuery(server, baseFilter, objType)
            if (matches) return matches.first()
        }
        return null
    }

    private <T extends LdapObject> List<T> searchMany(String baseFilter, Class<T> objType) {
        List<T> ret = []
        for (server in config.servers) {
            List<T> matches = doQuery(server, baseFilter, objType)
            if (matches) ret.addAll(matches)
        }
        return ret
    }

    private <T extends LdapObject> List<T> doQuery(Map server, String baseFilter, Class<T> objType) {
        if (!enabled) throw new RuntimeException('LdapService is not enabled.')

        boolean isPerson = objType == LdapPerson
        String host = server.host,
            filter = "(&(objectCategory=${isPerson ? 'Person' : 'Group'})$baseFilter)"

        cache.getOrCreate(host + filter) {
            withDebug(["Querying LDAP", [host: host, filter: filter]]) {
                LdapNetworkConnection conn
                try {

                    String baseDn = isPerson ? server.baseUserDn : server.baseGroupDn,
                        username = configService.getString('xhLdapUsername'),
                        password = configService.getPwd('xhLdapPassword')
                    String[] keys = objType.keys.toArray() as String[]

                    conn = new LdapNetworkConnection(host)
                    conn.timeOut = config.timeoutMs as Long
                    conn.bind(username, password)
                    conn.search(baseDn, filter, SearchScope.SUBTREE, keys)
                        .collect { objType.create(it.attributes as Collection<Attribute>) }
                } catch (Exception e) {
                    logError("Failure querying", [host: host, filter: filter], e)
                    return null
                } finally {
                    conn?.unBind()
                    conn?.close()
                }
            } as List<T>
        }
    }

    private Map getConfig() {
        configService.getMap('xhLdapConfig')
    }

    private void initCache() {
        cache = new Cache(svc: this, expireTime: config.cacheExpireSecs * SECONDS)
    }

    void clearCaches() {
        initCache()
        super.clearCaches()
    }
}
