/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
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

import java.time.Instant

import static io.xh.hoist.util.Utils.configService
import static io.xh.hoist.util.Utils.userService

/**
 * Managed wrapper around a raw WebSocketSession:
 *  - Adds thread safety, exception hardening, and timeouts via ConcurrentWebSocketSessionDecorator.
 *  - Looks up authorized/apparent HoistUsers from the session, accounting for admin impersonation.
 *  - Tracks basic metadata about connection status for display in the Hoist admin console.
 */
@Slf4j
@CompileStatic
class HoistWebSocketChannel implements LogSupport, JSONFormat {

    final WebSocketSession session
    final String authUsername
    final String apparentUsername
    final Instant createdTime

    private Integer sentMessageCount = 0
    private Instant lastSentTime
    private Integer receivedMessageCount = 0
    private Instant lastReceivedTime

    HoistWebSocketChannel(WebSocketSession webSocketSession) {
        Map conf = getConfig()
        def sendTimeLimit = (int) conf.sendTimeLimitMs,
            bufferSizeLimit = (int) conf.bufferSizeLimitBytes
        log.debug("Creating managed socket session: sendTimeLimit: $sendTimeLimit, bufferSizeLimit: $bufferSizeLimit")
        session = new ConcurrentWebSocketSessionDecorator(webSocketSession, sendTimeLimit, bufferSizeLimit)
        authUsername = getAuthUsernameFromSession()
        apparentUsername = getApparentUsernameFromSession()
        createdTime = Instant.now()
    }

    String getKey() {
        return "${authUsername}@${session.id}"
    }

    HoistUser getUser()         {getApparentUser()}
    HoistUser getAuthUser()     {userService.find(authUsername)}
    HoistUser getApparentUser() {userService.find(apparentUsername)}

    void sendMessage(TextMessage message) {
        try {
            session.sendMessage(message)
            sentMessageCount++
            lastSentTime = Instant.now()
        } catch (Exception e) {
            logErrorCompact("Failed to send message to $key", e)
        }
    }

    void noteMessageReceived() {
        receivedMessageCount++
        lastReceivedTime = Instant.now()
    }

    void close(CloseStatus status) {
        session.close(status)
    }

    //------------------------
    // Implementation
    //------------------------
    private String getAuthUsernameFromSession() {
        return (String) session.attributes[IdentityService.AUTH_USER_KEY] ?: 'unknownUser'
    }

    private String getApparentUsernameFromSession() {
        return (String) session.attributes[IdentityService.APPARENT_USER_KEY] ?: 'unknownUser'
    }

    private Map getConfig() {
        return configService.getMap('xhWebSocketConfig')
    }

    Map formatForJSON() {
        return [
            key: key,
            authUser: authUser,
            apparentUser: apparentUser,
            isOpen: session.isOpen(),
            createdTime: createdTime,
            sentMessageCount: sentMessageCount,
            lastSentTime: lastSentTime,
            receivedMessageCount: receivedMessageCount,
            lastReceivedTime: lastReceivedTime
        ]
    }
}
