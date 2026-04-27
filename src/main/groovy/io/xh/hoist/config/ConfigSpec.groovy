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
    Object defaultValue
    boolean clientVisible = false
    String groupName = 'Default'
    String note

    /**
     * Optional concrete {@link TypedConfigMap} subclass to bind to this config (JSON-type only).
     * When present:
     *  - Server code can load the config via {@link ConfigService#getObject(Class)}.
     *  - The class's property-initializer defaults are applied at read time for any key missing
     *    from the stored map — centralizing defaults next to the type.
     *  - A `WARN` is logged at startup for any key whose typed-class default differs from the
     *    BootStrap `defaultValue`, flagging drift between the two.
     */
    Class<? extends TypedConfigMap> typedClass
}
