/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.websocket

import groovy.transform.CompileStatic
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import org.springframework.util.MultiValueMap
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.util.UriComponentsBuilder

import java.time.Instant

import static io.xh.hoist.util.Utils.configService
import static io.xh.hoist.util.Utils.userService

/**
 * Managed wrapper around a raw WebSocketSession:
 *  - Adds thread safety, exception hardening, and timeouts via ConcurrentWebSocketSessionDecorator.
 *  - Looks up authorized/apparent HoistUsers from the session, accounting for admin impersonation.
 *  - Tracks basic metadata about connection status for display in the Hoist admin console.
 */
@CompileStatic
class HoistWebSocketChannel implements JSONFormat, LogSupport {

    final WebSocketSession session
    final String authUsername
    final String apparentUsername
    final String appBuild
    final String appVersion
    final Instant createdTime
    final String loadId
    final String tabId
    final String instance

    private Integer sentMessageCount = 0
    private Instant lastSentTime
    private Integer receivedMessageCount = 0
    private Instant lastReceivedTime

    HoistWebSocketChannel(WebSocketSession webSocketSession) {
        def conf = getConfig(),
            queryParams = getQueryParams(webSocketSession.uri),
            sendTimeLimit = (int) conf.sendTimeLimitMs,
            bufferSizeLimit = (int) conf.bufferSizeLimitBytes

        logDebug("Creating managed socket session", [sendTimeLimit: sendTimeLimit, bufferSizeLimit: bufferSizeLimit])

        session = new ConcurrentWebSocketSessionDecorator(webSocketSession, sendTimeLimit, bufferSizeLimit)
        authUsername = getAuthUsernameFromSession()
        apparentUsername = getApparentUsernameFromSession()
        appVersion = queryParams.getFirst('appVersion')
        appBuild = queryParams.getFirst('appBuild')
        instance = ClusterService.instanceName
        loadId = queryParams.getFirst('loadId')
        tabId = queryParams.getFirst('tabId')
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
            logError("Failed to send message to $key", e)
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

    private MultiValueMap<String, String> getQueryParams(URI uri) {
        UriComponentsBuilder.fromUri(uri).build().queryParams
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
            lastReceivedTime: lastReceivedTime,
            appVersion: appVersion,
            appBuild: appBuild,
            instance: instance,
            loadId: loadId,
            tabId: tabId,
        ]
    }
}
