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
 * `LdapService` for user/group directory lookups.
 */
class LdapConfig extends TypedConfigMap {

    /** Master switch — when false, LdapService returns no results and performs no binds. */
    boolean enabled = false

    /** Network timeout (milliseconds) applied to each LDAP connection. */
    Long timeoutMs = 60000L

    /** TTL (seconds) for cached lookup results. */
    Integer cacheExpireSecs = 300

    /**
     * When true, group-membership searches use the matching-rule-in-chain extension
     * (`LDAP_MATCHING_RULE_IN_CHAIN`) to efficiently resolve nested groups on AD. Use with
     * caution on non-AD directories that may not support the control.
     */
    boolean useMatchingRuleInChain = false

    /**
     * When true, the LDAP binding skips TLS certificate verification. Intended for dev
     * environments using self-signed certs — do not enable in production.
     */
    boolean skipTlsCertVerification = false

    /** One or more directory servers to consult, tried in order. */
    List<LdapServerOptions> servers = [new LdapServerOptions([:])]

    LdapConfig(Map args) { init(args) }

    /** Per-server connection and search-base settings. */
    static class LdapServerOptions extends TypedConfigMap {
        /** LDAP server hostname (no protocol/port — port is the LDAP default). */
        String host = ''
        /** Base DN for user searches, e.g. `'ou=users,dc=example,dc=com'`. */
        String baseUserDn = ''
        /** Base DN for group searches. */
        String baseGroupDn = ''

        LdapServerOptions(Map args) { init(args) }
    }
}
