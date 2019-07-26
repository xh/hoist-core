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

@CompileStatic
class WebSocketService extends BaseService implements EventPublisher {

    IdentityService identityService

    static String HEARTBEAT_TOPIC = 'xhHeartbeat'
    static String REG_SUCCESS_TOPIC = 'xhRegistrationSuccess'
    static String CHANNEL_OPENED_EVENT = 'xhWebSocketOpened'
    static String CHANNEL_CLOSED_EVENT = 'xhWebSocketClosed'
    static String MSG_RECEIVED_EVENT = 'xhRegistrationSuccess'

    private Map<WebSocketSession, HoistWebSocketChannel> _channels = new ConcurrentHashMap<>()

    Collection<HoistWebSocketChannel> getAllChannels() {
        _channels.values()
    }

    void pushToChannel(String channelKey, String topic, Object data) {
        pushToChannels([channelKey], topic, data)
    }

    void pushToChannels(Collection<String> channelKeys, String topic, Object data) {
        if (!channelKeys) return

        def textMessage = serialize(topic, data),
            channels = getChannelsForKeys(channelKeys),
            tasks = channels.collect{channel ->
                Promises.task{
                    channel.sendMessage(textMessage)
                }
            }

        // Relies on channel.sendMessage to catch/timeout.
        Promises.waitAll(tasks)
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
            notify(MSG_RECEIVED_EVENT, [channel: channel, message: msgJSON])
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
