/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.track

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhClientErrorConfig` soft config, controlling batched email
 * digest notifications for client error reports.
 */
class ClientErrorConfig extends TypedConfigMap {

    /** Interval (minutes) between digest email emissions. */
    Integer intervalMins = 2

    ClientErrorConfig(Map args) { init(args) }
}
