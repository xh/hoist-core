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
 * <p>Properties map directly to Graph `user` resource fields. If you need more fields, extend
 * this class, add matching properties, and override {@link #getKeys}.
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

    static EntraUser create(Map data) {
        def ret = new EntraUser()
        keys.each { ret[it] = data[it] }
        ret
    }

    /** Graph field names requested via `$select` and mapped onto properties of this class. */
    static List<String> getKeys() {
        [
            'id', 'userPrincipalName', 'displayName', 'givenName', 'surname', 'mail',
            'department', 'jobTitle', 'officeLocation', 'accountEnabled',
            'onPremisesSamAccountName'
        ]
    }

    Map formatForJSON() {
        keys.collectEntries { [it, this[it]] }
    }
}
