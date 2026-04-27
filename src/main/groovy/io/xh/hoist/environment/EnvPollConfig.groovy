/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.environment

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhEnvPollConfig` soft config, governing client polling for
 * version / instance / auth changes. Delivered to clients via the `/xh/environment` and
 * `/xh/environmentPoll` endpoint payloads (NOT via `clientVisible`) — the server embeds the
 * current value on every response so active clients pick up changes without a reload.
 */
class EnvPollConfig extends TypedConfigMap {

    /** Client poll frequency (seconds). -1 disables polling entirely. */
    Integer interval = 10

    /**
     * Client behavior when a new server version is detected. One of:
     *  - `forceReload` — refresh immediately (use when old clients are known incompatible)
     *  - `promptReload` — show update prompt banner, user chooses when to reload
     *  - `silent` — no action
     */
    String onVersionChange = 'promptReload'

    EnvPollConfig(Map args) { init(args) }
}
