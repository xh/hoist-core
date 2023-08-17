/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.websocket

import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.PerConnectionWebSocketHandler
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor

@EnableWebSocket
class HoistWebSocketConfigurer implements WebSocketConfigurer {

    @Override
    void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        def handler = new PerConnectionWebSocketHandler(HoistWebSocketHandler.class)
        registry.addHandler(handler, '/xhWebSocket')
            .addInterceptors(new HttpSessionHandshakeInterceptor())
            .setAllowedOrigins('*')
    }
}
