/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.websocket

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhWebSocketConfig` soft config, governing per-session limits
 * for Hoist's managed WebSocket channels.
 */
class WebSocketConfig extends TypedConfigMap {

    String getConfigName() { 'xhWebSocketConfig' }

    /**
     * Time (milliseconds) a blocking send may wait before the underlying session is closed.
     * Guards against slow/stuck clients holding server threads.
     */
    Integer sendTimeLimitMs = 1000

    /** Maximum outbound message buffer size (bytes) per session. */
    Integer bufferSizeLimitBytes = 1000000

    WebSocketConfig(Map args) { init(args) }
}
