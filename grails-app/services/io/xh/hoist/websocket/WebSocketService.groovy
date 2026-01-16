/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.websocket

import grails.events.EventPublisher
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

import java.util.concurrent.ConcurrentHashMap

import static grails.async.Promises.task
import static grails.async.Promises.waitAll
import static io.xh.hoist.cluster.ClusterService.instanceName
import static io.xh.hoist.util.ClusterUtils.runOnAllInstances
import static io.xh.hoist.util.ClusterUtils.runOnAllInstancesAsJson
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
        pushToChannels([channelKey], topic, data)
    }

    /**
     * Push a message to a collection of channels. Requests to send to an unknown or disconnected
     * client will be silently dropped.
     *
     * Method will return, when all servers have completed the task.  Exceptions will be caught and
     * logged, and not expected to be thrown.
     */
    void pushToChannels(Collection<String> channelKeys, String topic, Object data) {
        if (!channelKeys) return

        def msg = serialize(topic, data),
            byInstance = channelKeys.groupBy { instanceFromKey(it) },
            tasks = byInstance.collect { instance, keys ->
                task {
                    instance == instanceName ?
                        pushToChannelsInternal(msg, keys) :
                        runOnInstance(this.&pushToChannelsInternal, instance, [msg, keys])

                }
            }
        waitAll(tasks)
    }

    /**
     * Push a message to all channels in the cluster.
     *
     * Method will return, when all servers have completed the task.  Exceptions will be
     * caught and logged, and not expected to be thrown.
     */
    void pushToAllChannels(String topic, Object data) {
        runOnAllInstances(this.&pushToChannelsInternal, [serialize(topic, data), null])
    }

    /**
     * Pushes a message to all channels on the local instance.
     *
     * Method will return, when all servers have completed the task.  Exceptions will be
     * caught and logged, and not expected to be thrown.
     */
    void pushToLocalChannels(String topic, Object data) {
        pushToChannelsInternal(serialize(topic, data), null)
    }

    /**
     * Get all channels on the cluster.
     *
     * Note that the full WebSocketSession is not serializable across instances,
     * and so not available. This method will return the JSON serialized version
     * of the session, containing all of its important metadata. See getLocalChannels()
     * for a method that will return the full channels on the local instance.
     *
     * @returns collection of channels in simplified JSON form.
     */
    Collection<Map> getAllChannels() {
        runOnAllInstancesAsJson(this.&getLocalChannels)
            .collectMany { it.value.exception ? [] : JSONParser.parseArray(it.value.value as String) }
            as Collection<Map>
    }

    /**
     * Get all channels on the local instance.
     */
    Collection<HoistWebSocketChannel> getLocalChannels() {
        _channels.values()
    }

    /** Verifies that a channel remains actively connected and registered.*/
    boolean hasChannel(String channelKey) {
        def instance = instanceFromKey(channelKey)
        if (instance == instanceName) return hasLocalChannel(channelKey)

        def result = runOnInstance(this.&hasLocalChannel, instance, [channelKey])
        return result.value != null ? result.value as boolean : false
    }

    /** Verifies that a channel on the local instance remains actively connected and registered.*/
    boolean hasLocalChannel(String channelKey) {
        instanceFromKey(channelKey) == instanceName && _channels.any { it.value.key == channelKey}
    }

    //------------------------
    // Hoist Entry Points
    //------------------------
    /** @internal */
    void registerSession(WebSocketSession session) {
        def channel = _channels[session] = new HoistWebSocketChannel(session)
        sendMessage(channel, REG_SUCCESS_TOPIC, [channelKey: channel.key])
        notify(CHANNEL_OPENED_EVENT, channel)
        logDebug("Registered session", channel.key)
    }

    /** @internal */
    void unregisterSession(WebSocketSession session, CloseStatus closeStatus) {
        def channel = _channels.remove(session)
        if (channel) {
            notify(CHANNEL_CLOSED_EVENT, channel)
            logDebug("Closed session", channel.key, closeStatus)
        }
    }

    /** @internal */
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
    private String instanceFromKey(String channelKey) {
        channelKey.split('\\|')[1]
    }

    private void pushToChannelsInternal(TextMessage textMessage, Collection<String> keys) {
        def channels = keys != null ? getLocalChannelsForKeys(keys) : _channels.values()

        if (!channels) return

        // Note that we are relying on channel.sendMessage to catch/timeout.
        // Avoid async overhead for common single channel case
        if (channels.size() == 1) {
            channels.first().sendMessage(textMessage)
        } else {
            def tasks = channels.collect { c -> task { c.sendMessage(textMessage) } }
            waitAll(tasks)
        }
    }

    private void sendMessage(HoistWebSocketChannel channel, String topic, Object data) {
        channel.sendMessage(serialize(topic, data))
    }

    private TextMessage serialize(String topic, Object data) {
        def jsonString = JSONSerializer.serialize([topic: topic, data: data])
        new TextMessage(jsonString)
    }

    private Map deserialize(TextMessage message) {
        JSONParser.parseObject(message.payload)
    }

    private Collection<HoistWebSocketChannel> getLocalChannelsForKeys(Collection<String> channelKeys) {
        localChannels.findAll { channelKeys.contains(it.key) }
    }

    void clearCaches() {
        _channels.values().each { channel ->
            channel.close(CloseStatus.SERVICE_RESTARTED)
        }
        _channels.clear()

        super.clearCaches()
    }
}
