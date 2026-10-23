/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.entra

import io.xh.hoist.json.JSONFormat

/**
 * A user returned by {@link EntraIdService} queries against Microsoft Graph.
 *
 * <p>Properties map directly to Graph `user` resource fields. The set of fields requested and
 * populated is fixed - EntraIdService does not currently support custom subclasses. Apps that
 * need additional Graph fields should query Graph directly.
 */
class EntraUser implements JSONFormat {

    /** Entra ID object ID (GUID) - unique and stable through renames. */
    String id

    /** Primary sign-in identifier, e.g. 'jdoe@example.com'. */
    String userPrincipalName

    String displayName
    String givenName
    String surname
    String mail
    String department
    String jobTitle
    String officeLocation
    Boolean accountEnabled

    /** Legacy on-prem AD sAMAccountName, populated only for accounts synced from on-prem AD. */
    String onPremisesSamAccountName

    /**
     * Hybrid identity anchor linking a synced account to its on-prem AD object - the base64
     * form of the on-prem `ms-DS-ConsistencyGuid` / `objectGUID`. Populated only for accounts
     * synced from on-prem AD. The value is case-sensitive base64, so it is not a permitted
     * `usernameAttribute` (which lowercases its values). See {@link #getOnPremisesObjectGuid}
     * for the decoded GUID string form.
     */
    String onPremisesImmutableId

    /**
     * On-prem AD security identifier (SID) in its string form (`S-1-5-21-...`), populated only
     * for accounts synced from on-prem AD. Directly comparable to SIDs from token claims or
     * on-prem sources - no decoding required.
     */
    String onPremisesSecurityIdentifier

    /**
     * True if the account currently syncs from on-prem AD, false if it synced previously but
     * no longer does, null if it never has (cloud-only). Use to tell a legitimately cloud-only
     * account from a synced account whose on-prem attributes unexpectedly failed to resolve -
     * a null `onPremises*` value alone is ambiguous between the two.
     */
    Boolean onPremisesSyncEnabled

    /**
     * The {@link #onPremisesImmutableId} decoded to its GUID string form - the on-prem sync
     * anchor (`ms-DS-ConsistencyGuid` / `objectGUID`, whichever the tenant's Entra Connect
     * config anchors on) in the display form used by on-prem AD tools. Null when unset or not
     * a 16-byte value.
     *
     * <p>Windows GUIDs are mixed-endian: the first three groups are stored little-endian, so a
     * naive hex dump of the decoded bytes yields a well-formed but *wrong* GUID. This accessor
     * applies the required byte swaps. Derived on read - not a Graph field, and not serialized
     * by {@link #formatForJSON}.
     */
    String getOnPremisesObjectGuid() {
        if (!onPremisesImmutableId) return null
        byte[] b
        try {
            b = Base64.decoder.decode(onPremisesImmutableId)
        } catch (IllegalArgumentException ignored) {
            return null
        }
        if (b.length != 16) return null
        def hex = { int i -> String.format('%02x', b[i] & 0xFF) }
        return hex(3) + hex(2) + hex(1) + hex(0) + '-' +
            hex(5) + hex(4) + '-' +
            hex(7) + hex(6) + '-' +
            hex(8) + hex(9) + '-' +
            hex(10) + hex(11) + hex(12) + hex(13) + hex(14) + hex(15)
    }

    static EntraUser create(Map data) {
        def ret = new EntraUser()
        keys.each { ret[it] = data[it] }
        ret
    }

    /** Graph fields suitable for use as `xhEntraIdConfig.usernameAttribute`. */
    static List<String> getUsernameKeys() {
        ['userPrincipalName', 'mail', 'onPremisesSamAccountName']
    }

    /**
     * Graph fields suitable as the `field` argument to `EntraIdService.findUsers` - the
     * String-typed subset of {@link #getKeys}, as `eq` filters emit quoted string literals.
     */
    static List<String> getQueryKeys() {
        [
            'id', 'userPrincipalName', 'displayName', 'givenName', 'surname', 'mail',
            'department', 'jobTitle', 'officeLocation',
            'onPremisesSamAccountName', 'onPremisesImmutableId', 'onPremisesSecurityIdentifier'
        ]
    }

    /** Graph field names requested via `$select` and mapped onto properties of this class. */
    static List<String> getKeys() {
        [
            'id', 'userPrincipalName', 'displayName', 'givenName', 'surname', 'mail',
            'department', 'jobTitle', 'officeLocation', 'accountEnabled',
            'onPremisesSamAccountName', 'onPremisesImmutableId', 'onPremisesSecurityIdentifier',
            'onPremisesSyncEnabled'
        ]
    }

    Map formatForJSON() {
        keys.collectEntries { [it, this[it]] }
    }
}
