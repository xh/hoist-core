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
 * <p>Note that the tenant ID, client ID, and client secret used to acquire Microsoft Graph
 * access tokens are held separately, in the standalone `xhEntraTenantId`, `xhEntraClientId`,
 * and `xhEntraClientSecret` configs. Those identify the tenant and app registration for the
 * application as a whole and can be referenced by other subsystems (e.g. client-side OAuth).
 */
class EntraIdConfig extends TypedConfigMap {

    /** Master switch - when false, all EntraIdService query methods will throw. */
    boolean enabled = false

    /**
     * Graph user attribute mapped to the Hoist username when resolving group members - must be
     * one of {@link EntraUser#getUsernameKeys} (`userPrincipalName`, `mail`, or
     * `onPremisesSamAccountName`). Values are lowercased. Members with no value for this
     * attribute are excluded from results.
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
