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
}
