/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.config

/**
 * Base class for typed representations of Hoist soft-config map values.
 *
 * Subclasses simply declare their properties — the constructor assigns matching
 * keys from the provided map and silently ignores any extras.
 */
abstract class TypedConfigMap {
    TypedConfigMap(Map args) {
        args.each { k, v -> if (this.hasProperty(k)) this[k] = v }
    }
}
