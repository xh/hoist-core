package io.xh.hoist.ldap

import groovy.transform.MapConstructor

/**
 * Typed representation of `xhLdapConfig` values.
 */
@MapConstructor
class LdapConfig {
    Boolean enabled
    Integer timeoutMs
    Integer cacheExpireSecs
    List<LdapServerConfig> servers
}

@MapConstructor
class LdapServerConfig {
    String host
    String baseUserDn
    String baseGroupDn
}
