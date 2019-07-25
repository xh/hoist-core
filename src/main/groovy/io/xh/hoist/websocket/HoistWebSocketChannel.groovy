/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.websocket

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator

import static io.xh.hoist.util.Utils.getUserService

@Slf4j
@CompileStatic
class HoistWebSocketChannel implements LogSupport, JSONFormat {

    static int SEND_TIME_LIMIT_MS = 1000
    static int BUFFER_SIZE_LIMIT_BYTES = 1000000

    final WebSocketSession session
    final String username
    final Date dateCreated
    CloseStatus closeStatus

    HoistWebSocketChannel(WebSocketSession webSocketSession) {
        session = new ConcurrentWebSocketSessionDecorator(webSocketSession, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES)
        username = getSessionUsername()
        dateCreated = new Date()
    }

    String getKey() {
        return "${username}@${session.id}"
    }

    HoistUser getUser() {
        return userService.find(username)
    }

    void sendMessage(TextMessage message) {
        try {
            session.sendMessage(message)
        } catch (Exception e) {
            logErrorCompact("Failed to send message to $key", e)
        }
    }

    void close(CloseStatus status) {
        session.close(status)
        closeStatus = status
    }


    //------------------------
    // Implementation
    //------------------------
    private String getSessionUsername() {
        return (String) session.attributes[IdentityService.APPARENT_USER_KEY] ?: 'unknownUser'
    }

    Map formatForJSON() {
        return [
            id: session.id,
            user: user,
            isOpen: session.isOpen(),
            closeStatus: closeStatus?.reason,
            dateCreated: dateCreated
        ]
    }
}
