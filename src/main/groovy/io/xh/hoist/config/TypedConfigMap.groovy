/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.config

import io.xh.hoist.log.LogSupport

/**
 * Base class for typed representations of Hoist soft-config map values.
 *
 * Subclasses declare their properties (with optional default initializers) and must call
 * {@link #init} in their own constructor body. Unknown keys are skipped with a warning so
 * stale or mistyped entries in soft config are visible without breaking startup.
 *
 * <pre>
 * {@code
 * class MyConfig extends TypedConfigMap {
 *     boolean enabled = false        // default applied when key is missing from map
 *     String endpoint
 *
 *     MyConfig(Map args) { init(args) }
 * }
 * }
 * </pre>
 */
abstract class TypedConfigMap implements LogSupport {
    /** Copy keys from {@code args} into matching properties. Unknown keys are logged and skipped. */
    protected void init(Map args) {
        args?.each { k, v ->
            if (this.hasProperty(k as String)) {
                this[k as String] = v
            } else {
                instanceLog.logWarn("Unknown key '$k' - ignoring")
            }
        }
    }
}
