/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.pref

import groovy.transform.MapConstructor

/**
 * Typed specification for a required preference to be created by
 * {@link PrefService#ensureRequiredPrefsCreated}.
 *
 * Mirrors the seedable fields of {@link Preference} — if a new seedable field is added to the
 * domain class, it should be added here as well.
 *
 * Provides IDE autocomplete and compile-time validation for preference definitions.
 */
@MapConstructor
class PreferenceSpec {
    String name
    String type
    Object defaultValue
    String groupName = 'Default'
    String notes

    /**
     * Any violations of the {@link Preference} field length limits by this spec, as ready-to-log
     * messages. Empty if the spec is valid.
     *
     * Checked by {@link PrefService#ensureRequiredPrefsCreated} for *all* specs, including those
     * whose preference already exists in the database. Notes are seeded on create only, so an
     * over-length note would otherwise go unnoticed in established environments and surface only
     * when the app is next booted against a fresh database.
     */
    List<String> getValidationErrors() {
        List<String> ret = []
        if (name?.length() > Preference.MAX_NAME_LENGTH) {
            ret << "Preference '$name': name is ${name.length()} chars, exceeding the ${Preference.MAX_NAME_LENGTH} char limit".toString()
        }
        if (notes?.length() > Preference.MAX_NOTES_LENGTH) {
            ret << "Preference '$name': notes is ${notes.length()} chars, exceeding the ${Preference.MAX_NOTES_LENGTH} char limit".toString()
        }
        return ret
    }
}
