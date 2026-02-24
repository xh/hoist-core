/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.websocket

import grails.events.EventPublisher
import groovy.transform.CompileStatic
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Tags
import io.xh.hoist.BaseService
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.telemetry.MetricsService
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

import java.util.concurrent.ConcurrentHashMap
import static io.xh.hoist.cluster.ClusterService.instanceName
import static io.xh.hoist.util.AsyncUtils.asyncEach
import static io.xh.hoist.util.ClusterUtils.runOnAllInstances
import static io.xh.hoist.util.ClusterUtils.runOnAllInstancesAsJson
import static io.xh.hoist.util.ClusterUtils.runOnInstance
import static io.xh.hoist.util.Utils.grailsConfig
import static java.util.Map.Entry


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

    MetricsService metricsService

    private Map<WebSocketSession, HoistWebSocketChannel> _channels = new ConcurrentHashMap<>()

    private Gauge channelGauge
    private Counter sentCounter
    private Counter receivedCounter
    private Counter sendErrorCounter
    private Counter sessionsOpenedCounter
    private Counter sessionsClosedCounter

    void init() {
        initMetrics()
    }

    boolean isEnabled() {
        grailsConfig.getProperty('hoist.enableWebSockets', Boolean)
    }

    /**
     * Push a message to a connected client, as identified by its channel key.
     *
     * Channel can be connected to any instance on the cluster - the implementation will route it
     * accordingly, with no extra overhead for a local channel.
     *
     * Requests to send to an unknown or disconnected client will be silently dropped.
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
     * Channels can be connected to any instance on the cluster - the implementation will route them
     * accordingly, with no extra overhead for local channels.
     *
     * Method will return when all instances have completed the task. Exceptions will be caught and
     * logged, and not expected to be thrown.
     *
     * @param channelKeys - unique client connection identifiers, as provided to the caller by
     *      clients via app-specific calls to register their interest in a given topic.
     * @param topic - app-specific category/tag for message routing and identification.
     * @param data - message contents, to be serialized as JSON.
     */
    void pushToChannels(Collection<String> channelKeys, String topic, Object data) {
        if (!channelKeys) return

        def msg = serialize(topic, data),
            byInstance = channelKeys.groupBy { instanceFromKey(it) }

        asyncEach(byInstance.entrySet()) { Entry e ->
            def instance = e.key as String,
                keys = e.value as List<String>
            instance == instanceName ?
                pushInternal(keys, msg) :
                runOnInstance(this.&pushInternal, instance, [keys, msg])
        }
    }

    /**
     * Push a message to all channels in the cluster and return when all instances are complete.
     * Exceptions will be caught and logged - this method is not expected to throw.
     *
     * @param topic - app-specific category/tag for message routing and identification.
     * @param data - message contents, to be serialized as JSON.
     */
    void pushToAllChannels(String topic, Object data) {
        runOnAllInstances(this.&pushInternal, [null, serialize(topic, data)])
    }

    /**
     * Push a message to all channels on the local instance and return when complete.
     * Exceptions will be caught and logged - this method is not expected to throw.
     *
     * @param topic - app-specific category/tag for message routing and identification.
     * @param data - message contents, to be serialized as JSON.
     */
    void pushToLocalChannels(String topic, Object data) {
        pushInternal(null, serialize(topic, data))
    }

    /**
     * Get all channels on the cluster.
     *
     * Note that `HoistWebSocketChannel` with its embedded `WebSocketSession` is not serializable
     * across instances, and so cannot be returned here as instances of that class. Instead this
     * method returns the serialized output of {@link HoistWebSocketChannel#formatForJSON}.
     *
     * See {@link #getLocalChannels} for a method that will list channels on local instance only.
     *
     * @return collection of channels, serialized as Maps.
     */
    Collection<Map> getAllChannels() {
        runOnAllInstancesAsJson(this.&getLocalChannels)
            .collectMany { it.value.exception ? [] : JSONParser.parseArray(it.value.value as String) }
            as Collection<Map>
    }

    /**
     * Get all channels on the local instance.
     *
     * @return collection of channels on this instance.
     */
    Collection<HoistWebSocketChannel> getLocalChannels() {
        _channels.values()
    }

    /**
     * Verify that a channel remains actively connected and registered,
     * locally or with another instance within the cluster.
     *
     * @param channelKey - unique client connection identifier.
     * @return true if channel is connected and registered.
     */
    boolean hasChannel(String channelKey) {
        def instance = instanceFromKey(channelKey)
        if (instance == instanceName) return hasLocalChannel(channelKey)

        def result = runOnInstance(this.&hasLocalChannel, instance, [channelKey])
        return result.value != null ? result.value as boolean : false
    }

    /**
     * Verify that a channel on the local instance remains actively connected and registered.
     *
     * @param channelKey - unique client connection identifier.
     * @return true if channel is connected and registered on this instance.
     */
    boolean hasLocalChannel(String channelKey) {
        instanceFromKey(channelKey) == instanceName && _channels.any { it.value.key == channelKey }
    }

    //------------------------
    // Hoist Entry Points
    //------------------------
    /** @internal */
    void registerSession(WebSocketSession session) {
        def channel = _channels[session] = new HoistWebSocketChannel(session)
        sendMessage(channel, REG_SUCCESS_TOPIC, [channelKey: channel.key])
        notify(CHANNEL_OPENED_EVENT, channel)
        sessionsOpenedCounter?.increment()
        logDebug("Registered session", channel.key)
    }

    /** @internal */
    void unregisterSession(WebSocketSession session, CloseStatus closeStatus) {
        def channel = _channels.remove(session)
        if (channel) {
            notify(CHANNEL_CLOSED_EVENT, channel)
            sessionsClosedCounter?.increment()
            logDebug("Closed session", channel.key, closeStatus)
        }
    }

    /** @internal */
    void onMessage(WebSocketSession session, TextMessage message) {
        def channel = _channels[session]
        if (!channel) return

        channel.noteMessageReceived()
        receivedCounter?.increment()
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
    private void initMetrics() {
        def prefix = 'websocket',
            tags = Tags.of('source', 'infra'),
            registry = metricsService.registry

        channelGauge = Gauge.builder("${prefix}.channels", this, { _channels.size().toDouble() })
            .description('Active WebSocket channels')
            .tags(tags)
            .register(registry)

        sentCounter = Counter.builder("${prefix}.messages.sent")
            .description('Messages sent successfully')
            .tags(tags)
            .register(registry)

        receivedCounter = Counter.builder("${prefix}.messages.received")
            .description('Messages received from clients')
            .tags(tags)
            .register(registry)

        sendErrorCounter = Counter.builder("${prefix}.messages.sendErrors")
            .description('Message send failures')
            .tags(tags)
            .register(registry)

        sessionsOpenedCounter = Counter.builder("${prefix}.sessions.opened")
            .description('WebSocket sessions registered')
            .tags(tags)
            .register(registry)

        sessionsClosedCounter = Counter.builder("${prefix}.sessions.closed")
            .description('WebSocket sessions unregistered')
            .tags(tags)
            .register(registry)
    }

    private String instanceFromKey(String channelKey) {
        channelKey.split('\\|')[1]
    }

    private void pushInternal(Collection<String> channelKeys, TextMessage textMessage) {
        // Note that we are relying on channel.sendMessage to catch/timeout
        def channels = channelKeys != null ? getLocalChannelsForKeys(channelKeys) : _channels.values()
        asyncEach(channels) { HoistWebSocketChannel c ->
            c.sendMessage(textMessage) ? sentCounter?.increment() : sendErrorCounter?.increment()
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

    /** {@inheritDoc} */
    void clearCaches() {
        _channels.values().each { channel ->
            channel.close(CloseStatus.SERVICE_RESTARTED)
        }
        _channels.clear()

        super.clearCaches()
    }

    Map getAdminStats() {[
        channelCount: channelGauge?.value(),
        messagesSent: sentCounter?.count(),
        messagesReceived: receivedCounter?.count(),
        sendErrors: sendErrorCounter?.count(),
        sessionsOpened: sessionsOpenedCounter?.count(),
        sessionsClosed: sessionsClosedCounter?.count()
    ]}
}
