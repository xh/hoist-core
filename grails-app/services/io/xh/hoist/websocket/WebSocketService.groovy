package io.xh.hoist.websocket

import grails.async.Promises
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
class WebSocketService extends BaseService {

    IdentityService identityService

    private static String HEARTBEAT_TOPIC = 'xhHeartbeat'
    private Map<WebSocketSession, HoistWebSocketChannel> _channels = new ConcurrentHashMap<>()

    Collection<String> getChannelKeys() {
        _channels.collect {it.value.key}
    }

    void pushToAllChannels(String topic, Object data) {
        pushToChannels(channelKeys, topic, data)
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
        log.debug("Registered session | ${channel.key}")
        sendMessage(channel, 'registrationSuccess', [channelKey: channel.key])
    }

    void unregisterSession(WebSocketSession session, CloseStatus closeStatus) {
        def channel = _channels.remove(session)
        if (channel) {
            log.debug("Closed session | ${channel.key} | ${closeStatus.toString()}")
        }
    }

    void onMessage(WebSocketSession session, TextMessage message) {
        def channel = getChannel(session)
        if (!channel) return

        log.debug("Message received | ${channel.key} | ${message.payload}")
        def msgJSON = deserialize(message)

        if (msgJSON.topic == HEARTBEAT_TOPIC) {
            sendMessage(channel, HEARTBEAT_TOPIC, 'pong')
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

    private HoistWebSocketChannel getChannel(WebSocketSession session) {
        return _channels[session]
    }

    private Collection<HoistWebSocketChannel> getChannelsForKeys(Collection<String> channelKeys) {
        _channels.values().findAll{channelKeys.contains(it.key)}
    }

    void clearCaches() {
        this._channels.values().each{channel ->
            channel.close(CloseStatus.SERVICE_RESTARTED)
        }
        this._channels.clear()

        super.clearCaches()
    }

}
