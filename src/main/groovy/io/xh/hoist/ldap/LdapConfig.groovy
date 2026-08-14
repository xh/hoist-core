/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.ldap

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhLdapConfig` soft config, governing the optional
 * {@link LdapService} for user/group directory lookups.
 *
 * <p>Note that the credentials used to bind and query are held separately, in the `xhLdapUsername`
 * and `xhLdapPassword` configs.
 */
class LdapConfig extends TypedConfigMap {

    /** Master switch - when false, all LdapService query methods will throw. */
    boolean enabled = false

    /** Time (in milliseconds) to wait for any individual search to resolve. */
    Long timeoutMs = 60000L

    /** Time (in seconds) to cache lookup results. Set to -1 to disable caching. */
    Integer cacheExpireSecs = 300

    /**
     * When true, group-membership searches use Microsoft Active Directory's proprietary
     * "LDAP_MATCHING_RULE_IN_CHAIN" rule (the magic `1.2.840.113556.1.4.1941` OID) to resolve
     * nested groups in a single query, instead of walking nested groups recursively.
     *
     * <p>This can be more efficient, but should be used with caution - it can trigger a large
     * database walk with a significant performance impact, unnecessary when queries are not
     * expected to return deeply nested groups. Not supported by non-AD directories.
     */
    boolean useMatchingRuleInChain = false

    /**
     * When true, accept untrusted certificates when binding. Intended for dev environments
     * using self-signed certs - do not enable in production.
     */
    boolean skipTlsCertVerification = false

    /**
     * LDAP person attribute mapped to the Hoist username when resolving group members for role
     * management, e.g. `samaccountname` or `mail`. Values are lowercased. Members with no value
     * for this attribute are excluded from results.
     */
    String usernameAttribute = 'samaccountname'

    /** One or more directory servers to be queried, in the order listed. */
    List<LdapServerOptions> servers = [new LdapServerOptions([:])]

    LdapConfig(Map args) { init(args) }

    /** Per-server host and search-base settings. */
    static class LdapServerOptions extends TypedConfigMap {
        /** LDAP server hostname (no protocol or port - the LDAP default port is used). */
        String host = ''
        /** Base DN for user searches, e.g. `'ou=users,dc=example,dc=com'`. */
        String baseUserDn = ''
        /** Base DN for group searches. */
        String baseGroupDn = ''

        LdapServerOptions(Map args) { init(args) }
    }
}
