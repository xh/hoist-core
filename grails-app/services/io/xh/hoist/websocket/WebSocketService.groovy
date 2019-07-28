/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.websocket

import grails.async.Promises
import grails.events.EventPublisher
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.json.JSON
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.user.IdentityService
import org.grails.web.json.JSONObject
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

import java.util.concurrent.ConcurrentHashMap

/**
 * Service to maintain and provide access to a collection of "channels", each representing a unique
 * Hoist client application connected to this server via a WebSocket. Can send and receive messages
 * to these clients in the form of JSON messages tagged with a simple topic string.
 *
 * This service builds on top of the core websocket support provided by Spring and wired via
 * Application.groovy and the HoistWebSocketHandler class, which intercepts and relays connection
 * events here for bookkeeping. This service will also respond to heartbeat requests from clients
 * to keep sockets open and verify ongoing connectivity.
 *
 * Client apps are assigned a channelKey identifier upon successful connection and are expected to
 * present that to application-specific services when registering their interest in a given stream
 * of updates to be produced by the server. Those business services can then request that this
 * service deliver their messages to those clients. It will handle the JSON encoding.
 *
 * While not expected to be a common use case, incoming messages from clients will cause this
 * service to fire a MSG_RECEIVED_EVENT containing the sender channel, topic, and message data.
 * Application services could listen to and take actions based upon these events as needed.
 *
 * @see HoistWebSocketChannel
 * @see HoistWebSocketHandler
 */
@CompileStatic
class WebSocketService extends BaseService implements EventPublisher {

    IdentityService identityService

    static final String HEARTBEAT_TOPIC = 'xhHeartbeat'
    static final String REG_SUCCESS_TOPIC = 'xhRegistrationSuccess'
    static final String CHANNEL_OPENED_EVENT = 'xhWebSocketOpened'
    static final String CHANNEL_CLOSED_EVENT = 'xhWebSocketClosed'
    static final String MSG_RECEIVED_EVENT = 'xhWebSocketMessageReceived'

    private Map<WebSocketSession, HoistWebSocketChannel> _channels = new ConcurrentHashMap<>()

    /**
     * Push a message to a connected client, as identified by its channel key. Requests to send to
     * an unknown or disconnected client will be silently dropped.
     *
     * @param channelKey - unique client connection identifier, as provided to the caller by the
     *      client via app-specific calls to register its interest in a given topic.
     * @param topic - app-specific category/tag for message routing and identification.
     * @param data - message contents, to be serialized as JSON.
     */
    void pushToChannel(String channelKey, String topic, Object data) {
        pushToChannels([channelKey], topic, data)
    }

    /**
     * Push a message to a collection of channels. Requests to send to an unknown or disconnected
     * client will be silently dropped.
     *
     * TODO - return info on known/unknown channels to assist callers in culling dead subscriptions.
     */
    void pushToChannels(Collection<String> channelKeys, String topic, Object data) {
        if (!channelKeys) return

        def textMessage = serialize(topic, data),
            channels = getChannelsForKeys(channelKeys),
            tasks = channels.collect{channel ->
                Promises.task{channel.sendMessage(textMessage)}
            }

        // Relies on channel.sendMessage to catch/timeout.
        Promises.waitAll(tasks)
    }

    /**
     * Return all actively connected client sessions. Intended primarily for internal / admin use.
     */
    Collection<HoistWebSocketChannel> getAllChannels() {
        _channels.values()
    }

    /**
     * Verifies that a particular channel remains actively connected and registered.
     */
    boolean hasChannel(String channelKey) {
        return allChannels*.key.contains(channelKey)
    }

    //------------------------
    // Hoist Entry Points
    //------------------------
    void registerSession(WebSocketSession session) {
        def channel = _channels[session] = new HoistWebSocketChannel(session)
        sendMessage(channel, REG_SUCCESS_TOPIC, [channelKey: channel.key])
        notify(CHANNEL_OPENED_EVENT, channel)
        log.debug("Registered session | ${channel.key}")
    }

    void unregisterSession(WebSocketSession session, CloseStatus closeStatus) {
        def channel = _channels.remove(session)
        if (channel) {
            notify(CHANNEL_CLOSED_EVENT, channel)
            log.debug("Closed session | ${channel.key} | ${closeStatus.toString()}")
        }
    }

    void onMessage(WebSocketSession session, TextMessage message) {
        def channel = _channels[session]
        if (!channel) return

        channel.noteMessageReceived()
        log.debug("Message received | ${channel.key} | ${message.payload}")
        def msgJSON = deserialize(message)

        if (msgJSON.topic == HEARTBEAT_TOPIC) {
            sendMessage(channel, HEARTBEAT_TOPIC, 'pong')
        } else {
            notify(MSG_RECEIVED_EVENT, [channel: channel, topic: msgJSON.topic, data: msgJSON.data])
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private void sendMessage(HoistWebSocketChannel channel, String topic, Object data) {
        channel.sendMessage(serialize(topic, data))
    }

    private TextMessage serialize(String topic, Object data) {
        def jsonString = JSONSerializer.serialize([topic: topic, data: data])
        return new TextMessage(jsonString)
    }

    private JSONObject deserialize(TextMessage message) {
        return (JSONObject) JSON.parse(message.payload)
    }

    private Collection<HoistWebSocketChannel> getChannelsForKeys(Collection<String> channelKeys) {
        allChannels.findAll{channelKeys.contains(it.key)}
    }

    void clearCaches() {
        _channels.values().each{channel ->
            channel.close(CloseStatus.SERVICE_RESTARTED)
        }
        _channels.clear()

        super.clearCaches()
    }
}
