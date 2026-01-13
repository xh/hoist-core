/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.websocket

import grails.async.Promise
import grails.events.EventPublisher
import groovy.transform.CompileStatic
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterResult
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.user.IdentityService
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

import java.util.concurrent.ConcurrentHashMap

import static grails.async.Promises.task
import static grails.async.Promises.waitAll
import static io.xh.hoist.util.ClusterUtils.runOnAllInstances
import static io.xh.hoist.util.ClusterUtils.runOnInstance
import static io.xh.hoist.util.Utils.grailsConfig


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

    boolean isEnabled() {
        grailsConfig.getProperty('hoist.enableWebSockets', Boolean)
    }

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
        def instance = getInstanceNameFromKey(channelKey)
        if (instance == ClusterService.instanceName) {
            def channels = getLocalChannelsForKeys([channelKey])
            if (channels) {
                def textMessage = serialize(topic, data)
                channels.first().sendMessage(textMessage)
            }
        } else {
            runOnInstance(this.&pushToChannel, instance, [channelKey, topic, data])
        }
    }

    /**
     * Push a message to a collection of channels. Requests to send to an unknown or disconnected
     * client will be silently dropped.
     */
    void pushToChannels(Collection<String> channelKeys, String topic, Object data) {
        if (!channelKeys) return
        // The single channel case is extremely common, so avoid async overhead.
        // Note that we are relying on channel.sendMessage to catch/timeout.
        if (channelKeys.size() == 1) {
            pushToChannel(channelKeys.first(), topic, data)
        }
        def channelKeysByInstance = channelKeys.groupBy {getInstanceNameFromKey(it) }
        List<Promise> tasks = []
        channelKeysByInstance.each { String instance, List<String> instanceChannelKeys ->
            if (instance == ClusterService.instanceName) {
                def channels = getLocalChannelsForKeys(instanceChannelKeys)
                if (!channels) return
                def textMessage = serialize(topic, data)
                channels.each { c ->
                    tasks.push(task { c.sendMessage(textMessage) })
                }
            } else {
                tasks.push(task { runOnInstance(this.&pushToChannels, instance, [instanceChannelKeys, topic, data]) })
            }
        }
        waitAll(tasks)
    }

    /**
     * Pushes the message to all instances, to push into their own channels.
     */
    void pushToAllClusterChannels(String topic, Object data) {
        pushToChannels(allClusterChannelKeys, topic, data)
    }

    /**
     * Runs the when clause on all instances, pushing the message to every channel that passes it.
     */
    void pushToAllClusterChannelsWhere(
        String topic,
        Object data,
        @ClosureParams(value = SimpleType, options = ["io.xh.hoist.websocket.HoistWebSocketChannel"]) Closure<Boolean> when
    ) {
        runOnAllInstances(this.&pushToAllChannelsWhere, [topic, data, when])
    }

    /**
     * Runs the when clause against all local channels on this instance, and pushes the message to all that pass.
     */
    void pushToAllChannelsWhere(
        String topic,
        Object data,
        @ClosureParams(value = SimpleType, options = ["io.xh.hoist.websocket.HoistWebSocketChannel"]) Closure<Boolean> when
    ) {
        def channels = allChannels.findAll { when.call(it) }
        pushToChannels(channels.collect { it.key}, topic, data)
    }

    /**
     * Return all locally connected client sessions. Intended primarily for internal / admin use.
     */
    Collection<HoistWebSocketChannel> getAllChannels() {
        _channels.values()
    }

    /**
     * Return all locally connected client session keys.
     */
    Collection<String> getAllChannelKeys() {
        _channels.values().collect { it.key }
    }

    /**
     * Return all channel keys in the entire cluster.
     */
    Collection<String> getAllClusterChannelKeys() {
        return runOnAllInstances(this.&getAllChannelKeys).collectMany { String instance, ClusterResult result ->
            result.value as List<String> ?: []
        }
    }

    /**
     * Verifies that a particular channel remains actively connected and registered.
     */
    boolean hasChannel(String channelKey) {
        // If connected to this instance.
        if (isLocalInstance(channelKey)) {
            return allChannels*.key.contains(channelKey)
        }
        // Otherwise, ask relevant instance.
        return runOnInstance(this.&hasChannel, getInstanceNameFromKey(channelKey), [channelKey])
    }

    /**
     * True if the channel key belongs to this instance.
     */
    boolean isLocalInstance(String channelKey) {
        return getInstanceNameFromKey(channelKey) == ClusterService.instanceName
    }

    /**
     * Parses the channelKey String to extract the instance name from the end.
     */
    String getInstanceNameFromKey(String channelKey) {
        return channelKey.substring(channelKey.indexOf('.'))
    }

    //------------------------
    // Hoist Entry Points
    //------------------------
    void registerSession(WebSocketSession session) {
        def channel = _channels[session] = new HoistWebSocketChannel(session)
        sendMessage(channel, REG_SUCCESS_TOPIC, [channelKey: channel.key])
        notify(CHANNEL_OPENED_EVENT, channel)
        logDebug("Registered session", channel.key)
    }

    void unregisterSession(WebSocketSession session, CloseStatus closeStatus) {
        def channel = _channels.remove(session)
        if (channel) {
            notify(CHANNEL_CLOSED_EVENT, channel)
            logDebug("Closed session", channel.key, closeStatus)
        }
    }

    void onMessage(WebSocketSession session, TextMessage message) {
        def channel = _channels[session]
        if (!channel) return

        channel.noteMessageReceived()
        logDebug("Message received", channel.key, message.payload)
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

    private Map deserialize(TextMessage message) {
        return JSONParser.parseObject(message.payload)
    }

    private Collection<HoistWebSocketChannel> getLocalChannelsForKeys(Collection<String> channelKeys) {
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
