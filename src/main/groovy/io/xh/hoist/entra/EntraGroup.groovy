/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.entra

import io.xh.hoist.json.JSONFormat

/**
 * A group returned by {@link EntraIdService} queries against Microsoft Graph.
 *
 * <p>Properties map directly to Graph `group` resource fields. If you need more fields, extend
 * this class, add matching properties, and override {@link #getKeys}.
 */
class EntraGroup implements JSONFormat {

    /** Entra ID object ID (GUID) - unique and stable through renames. */
    String id

    String displayName
    String description
    String mail

    /** True for security groups, false for e.g. Microsoft 365 groups. */
    Boolean securityEnabled

    /** Graph group type markers, e.g. 'Unified' for Microsoft 365 groups. Empty for security groups. */
    List<String> groupTypes

    static EntraGroup create(Map data) {
        def ret = new EntraGroup()
        keys.each { ret[it] = data[it] }
        ret
    }

    /** Graph field names requested via `$select` and mapped onto properties of this class. */
    static List<String> getKeys() {
        ['id', 'displayName', 'description', 'mail', 'securityEnabled', 'groupTypes']
    }

    Map formatForJSON() {
        keys.collectEntries { [it, this[it]] }
    }
}
