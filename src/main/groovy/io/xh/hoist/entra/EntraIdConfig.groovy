/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.entra

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhEntraIdConfig` soft config, governing the optional
 * {@link EntraIdService} for Microsoft Entra ID user/group directory lookups.
 *
 * <p>Note that the client secret used to acquire Microsoft Graph access tokens is held
 * separately, in the `xhEntraIdClientSecret` config.
 */
class EntraIdConfig extends TypedConfigMap {

    /** Master switch - when false, all EntraIdService query methods will throw. */
    boolean enabled = false

    /** Entra ID tenant ID (GUID) to query. */
    String tenantId = ''

    /**
     * Client ID (GUID) of the Entra ID app registration used to query Microsoft Graph. The
     * registration must hold admin-consented application permissions - see the EntraIdService
     * class docs for the required permission set.
     */
    String clientId = ''

    /**
     * Graph user attribute mapped to the Hoist username when resolving group members, e.g.
     * `userPrincipalName`, `mail`, or `onPremisesSamAccountName`. Values are lowercased.
     * Members with no value for this attribute are excluded from results.
     */
    String usernameAttribute = 'userPrincipalName'

    /**
     * True to strip the domain from the username attribute value - e.g. `jdoe@example.com`
     * becomes `jdoe`. For apps whose Hoist usernames are the local part of the UPN or email.
     * Unlike `usernameAttribute: 'onPremisesSamAccountName'`, this mapping also works for
     * cloud-only accounts, which have no on-prem sAMAccountName.
     */
    boolean stripUsernameDomain = false

    /**
     * Time (in milliseconds) to wait for any individual Graph request to resolve, including
     * each page of a paged result. Graph typically responds well within a second or two - a
     * request slower than this default indicates a network or configuration problem.
     */
    Long timeoutMs = 10000L

    /** Time (in seconds) to cache lookup results. Set to -1 to disable caching. */
    Integer cacheExpireSecs = 300

    EntraIdConfig(Map args) { init(args) }
}
