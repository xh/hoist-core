/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import groovy.transform.MapConstructor

/**
 * Typed specification for a required config to be created by
 * {@link ConfigService#ensureRequiredConfigsCreated}.
 *
 * Mirrors the seedable fields of {@link AppConfig} — if a new seedable field is added to the
 * domain class, it should be added here as well.
 *
 * Provides IDE autocomplete and compile-time validation for config definitions.
 */
@MapConstructor
class ConfigSpec {
    String name
    String valueType

    /**
     * Value seeded when the config does not yet exist. Omit for configs with a {@link #typedClass} -
     * defaults live on the typed class, and the config is seeded with an empty JSON object.
     */
    Object defaultValue
    boolean clientVisible = false
    String groupName = 'Default'
    String note

    /**
     * Optional {@link TypedConfigMap} subclass binding the shape of this config (JSON-type only).
     * Recommended for any structured config with a stable key set — see docs/configuration.md.
     *
     * When set, leave {@link #defaultValue} unspecified. The typed class's property initializers
     * are the defaults, and a non-empty `defaultValue` logs a WARN at startup.
     */
    Class<? extends TypedConfigMap> typedClass
}
