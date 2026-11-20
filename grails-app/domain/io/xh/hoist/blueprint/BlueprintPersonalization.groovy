/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.blueprint

import io.xh.hoist.json.JSONFormat

/**
 * One user's personalization of a {@link Blueprint} for one preset, holding `set` and `remove`
 * ops and the publish number they were made against. Unique per blueprint, user and preset.
 */
class BlueprintPersonalization implements JSONFormat {

    /** Stored for a personalization outside every preset, as a unique index cannot rely on null. */
    static final String NO_PRESET = '(none)'

    String username
    String preset = NO_PRESET
    String ops = '[]'
    int publishVersion
    Date dateCreated
    Date lastUpdated

    static belongsTo = [blueprint: Blueprint]

    static mapping = {
        table 'xh_blueprint_personalization'
        version false
        blueprint index: 'idx_xh_blueprint_pers_blueprint'
        ops type: 'text'
    }

    static constraints = {
        username maxSize: 50, blank: false
        preset maxSize: 255, blank: false, unique: ['blueprint', 'username']
    }

    Map formatForJSON() {[
        id: id,
        username: username,
        preset: preset == NO_PRESET ? null : preset,
        publishVersion: publishVersion,
        lastUpdated: lastUpdated
    ]}
}
