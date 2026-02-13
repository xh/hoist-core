> **Status: DRAFT** — This document is awaiting review. See
> [`README-ROADMAP.md`](./README-ROADMAP.md) for the documentation review workflow.

# WebSocket Push

## Overview

Hoist-core provides a **cluster-aware WebSocket push system** that allows server-side services to
send real-time messages to connected browser clients. This is the primary mechanism for pushing
data updates, notifications, and config change alerts from the server to the client without
requiring the client to poll.

The system is built on Spring's native WebSocket support and managed by `WebSocketService`, a
Grails service that wraps raw `WebSocketSession` objects in `HoistWebSocketChannel` instances.
Each channel represents a single connected Hoist client tab and carries metadata about the
authenticated user, app version, and connection health.

**Why cluster-aware?** In multi-instance deployments behind a load balancer, a client's WebSocket
connection lands on one specific server instance, but the business logic that produces a push
message may run on any instance (e.g. a `primaryOnly` timer). As of hoist-core v36, the push
methods automatically route messages to the correct instance using Hazelcast distributed
execution — callers no longer need to know which instance holds a given channel.

**Key design decisions:**

- **Channels, not topics.** The server addresses messages to specific channel keys or broadcasts
  to all channels. Topic-based subscription management is left to application code — the
  framework provides the transport.
- **Fire-and-forget delivery.** Messages to disconnected or unknown channels are silently dropped.
  This simplifies caller code and avoids error cascades from transient disconnections.
- **JSON wire format.** All messages are serialized as `{topic, data}` JSON payloads using
  Hoist's `JSONSerializer`, ensuring consistent serialization behavior with the rest of the
  framework.

## Source Files

| File | Location | Role |
|------|----------|------|
| `WebSocketService` | `grails-app/services/io/xh/hoist/websocket/` | Primary service — push API, channel registry, cluster routing, heartbeat handling |
| `HoistWebSocketChannel` | `src/main/groovy/io/xh/hoist/websocket/` | Managed channel wrapper — thread safety, user lookup, connection metadata |
| `HoistWebSocketHandler` | `src/main/groovy/io/xh/hoist/websocket/` | Spring `TextWebSocketHandler` — relays connection events to `WebSocketService` |
| `HoistWebSocketConfigurer` | `src/main/groovy/io/xh/hoist/websocket/` | Spring `@EnableWebSocket` configurer — registers handler at `/xhWebSocket` |
| `WebSocketAdminController` | `grails-app/controllers/io/xh/hoist/admin/cluster/` | Admin endpoint — list channels and push test messages (cluster-routed) |
| `ClientAdminController` | `grails-app/controllers/io/xh/hoist/admin/` | Legacy admin endpoint — list all clients and push messages |
| `ApplicationConfig` | `src/main/groovy/io/xh/hoist/configuration/` | Default Grails config — sets `hoist.enableWebSockets = true` |
| `HoistCoreGrailsPlugin` | `src/main/groovy/io/xh/hoist/` | Plugin descriptor — conditionally registers `HoistWebSocketConfigurer` bean |

## Key Classes

### WebSocketService

`grails-app/services/io/xh/hoist/websocket/WebSocketService.groovy`

The central service for WebSocket push. It maintains an in-memory `ConcurrentHashMap` of
`WebSocketSession` to `HoistWebSocketChannel` mappings for channels connected to the local
instance, and provides cluster-aware methods to push messages to any channel in the cluster.

#### Enabled check

WebSocket support is gated by the Grails application config `hoist.enableWebSockets` (default
`true` since hoist-core v13). The `isEnabled()` method reads this flag and is used by
`EnvironmentService` to report WebSocket availability to clients:

```groovy
boolean isEnabled() {
    grailsConfig.getProperty('hoist.enableWebSockets', Boolean)
}
```

To disable WebSockets, set `hoist.enableWebSockets = false` in your application's
`application.groovy`.

#### Push API

| Method | Description |
|--------|-------------|
| `pushToChannel(channelKey, topic, data)` | Push to a single channel, anywhere in the cluster |
| `pushToChannels(channelKeys, topic, data)` | Push to multiple channels, routing each to the correct instance |
| `pushToAllChannels(topic, data)` | Broadcast to every connected channel across all instances |
| `pushToLocalChannels(topic, data)` | Broadcast to channels on this instance only |

All push methods serialize the message once as a `TextMessage` containing
`{topic: String, data: Object}` JSON, then deliver it. Messages to unknown or disconnected
channels are silently dropped — these methods do not throw.

**Cluster routing in `pushToChannels`:** The channel key format is
`{authUsername}|{instanceName}|{uuid}`, so the service extracts the instance name from the key,
groups channels by instance, and dispatches in parallel. Local channels are pushed directly;
remote channels are pushed via `ClusterUtils.runOnInstance()`, which executes the push on the
target instance through Hazelcast's distributed execution framework.

```groovy
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
```

#### Channel query API

| Method | Description |
|--------|-------------|
| `getAllChannels()` | Returns `Collection<Map>` — serialized metadata for all channels across the cluster |
| `getLocalChannels()` | Returns `Collection<HoistWebSocketChannel>` — live channel objects on this instance |
| `hasChannel(channelKey)` | Checks cluster-wide whether a channel is connected |
| `hasLocalChannel(channelKey)` | Checks this instance only |

`getAllChannels()` returns Maps (not `HoistWebSocketChannel` objects) because the channel's
embedded `WebSocketSession` is not serializable across instances. The Maps contain the fields
from `HoistWebSocketChannel.formatForJSON()`:

```
key, authUser, apparentUser, isOpen, createdTime, sentMessageCount,
lastSentTime, receivedMessageCount, lastReceivedTime, appVersion,
appBuild, clientAppCode, instance, loadId, tabId
```

#### Events

`WebSocketService` publishes three Grails events via `EventPublisher.notify()`:

| Constant | Event Name | Payload | When |
|----------|-----------|---------|------|
| `CHANNEL_OPENED_EVENT` | `xhWebSocketOpened` | `HoistWebSocketChannel` | New channel registered |
| `CHANNEL_CLOSED_EVENT` | `xhWebSocketClosed` | `HoistWebSocketChannel` | Channel disconnected |
| `MSG_RECEIVED_EVENT` | `xhWebSocketMessageReceived` | `Map [channel, topic, data]` | Client sends a non-heartbeat message |

These are **local** Grails events (not Hazelcast topics), so they fire only on the instance where
the channel is connected. Application services can subscribe to them using `BaseService.subscribe()`:

```groovy
class MyService extends BaseService {
    void init() {
        subscribe(WebSocketService.CHANNEL_OPENED_EVENT) { HoistWebSocketChannel channel ->
            logInfo("New connection from ${channel.apparentUsername}")
        }
    }
}
```

#### Heartbeat handling

When a client sends a message with topic `xhHeartbeat`, the service replies with
`{topic: 'xhHeartbeat', data: 'pong'}`. This keeps the WebSocket connection alive through
proxies and load balancers and lets the client verify connectivity. Non-heartbeat incoming
messages fire `MSG_RECEIVED_EVENT`.

#### clearCaches

The `clearCaches()` override closes all local WebSocket sessions with
`CloseStatus.SERVICE_RESTARTED` and clears the channel map. This is triggered by the Hoist admin
"Clear Caches" action and will cause clients to reconnect.

### HoistWebSocketChannel

`src/main/groovy/io/xh/hoist/websocket/HoistWebSocketChannel.groovy`

A managed wrapper around a raw `WebSocketSession` that adds:

- **Thread safety** — Wraps the session in a `ConcurrentWebSocketSessionDecorator` with
  configurable send-time limit and buffer size limit (from `xhWebSocketConfig`).
- **User identity** — Reads `authUsername` and `apparentUsername` strings from the session's HTTP
  session attributes (set during the handshake by `HoistFilter`/authentication). Accounts for
  admin impersonation by tracking both identities. Provides `getAuthUser()` and
  `getApparentUser()` accessors that look up the corresponding live `HoistUser` objects via
  `userService.find()`. The `getUser()` accessor is an alias for `getApparentUser()`.
- **Client metadata** — Extracts `appVersion`, `appBuild`, `loadId`, `tabId`, and
  `clientAppCode` from the WebSocket connection URI's query parameters.
- **Connection tracking** — Records `createdTime`, `sentMessageCount`, `receivedMessageCount`,
  and their timestamps for display in the Admin Console.

#### Channel key format

Each channel is assigned a unique key on construction:

```
{authUsername}|{instanceName}|{8-char-uuid}
```

For example: `jsmith|hoist-app-1|a3f8b2c1`

This format is significant — `WebSocketService` parses the instance name from the key to route
messages to the correct cluster member. The key is sent to the client upon successful
registration (topic `xhRegistrationSuccess`, data `{channelKey: ...}`) and must be stored by
the client for subsequent use.

#### sendMessage

The `sendMessage` method wraps all sends in a try/catch — failures are logged but never
propagated. This is critical for the fire-and-forget push model:

```groovy
void sendMessage(TextMessage message) {
    try {
        session.sendMessage(message)
        sentMessageCount++
        lastSentTime = Instant.now()
    } catch (Exception e) {
        logError("Failed to send message to $key", e)
    }
}
```

#### JSON serialization

Implements `JSONFormat` via `formatForJSON()`, returning a Map of channel metadata. This is what
`WebSocketService.getAllChannels()` serializes when aggregating channels across instances.

### HoistWebSocketHandler

`src/main/groovy/io/xh/hoist/websocket/HoistWebSocketHandler.groovy`

A thin Spring `TextWebSocketHandler` that relays three lifecycle events to `WebSocketService`:

| Spring callback | Delegates to |
|----------------|-------------|
| `afterConnectionEstablished(session)` | `webSocketService.registerSession(session)` |
| `handleTextMessage(session, message)` | `webSocketService.onMessage(session, message)` |
| `afterConnectionClosed(session, status)` | `webSocketService.unregisterSession(session, status)` |

This class contains no business logic — it exists solely to bridge Spring's WebSocket
infrastructure into the Hoist service layer. A new handler instance is created per connection
via `PerConnectionWebSocketHandler`.

### HoistWebSocketConfigurer

`src/main/groovy/io/xh/hoist/websocket/HoistWebSocketConfigurer.groovy`

A Spring `WebSocketConfigurer` annotated with `@EnableWebSocket`. Registers the WebSocket
endpoint at the path `/xhWebSocket` with:

- A `PerConnectionWebSocketHandler` wrapping `HoistWebSocketHandler` (one handler instance per
  connection).
- An `HttpSessionHandshakeInterceptor` that copies HTTP session attributes into the WebSocket
  session — this is how authentication context (set by `HoistFilter`) becomes available to
  `HoistWebSocketChannel`.
- `setAllowedOrigins('*')` — origin checking is intentionally permissive since authentication is
  handled at the HTTP session level.

This bean is conditionally registered by `HoistCoreGrailsPlugin` only when
`hoist.enableWebSockets` is `true`.

## Configuration

### Grails application config

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `hoist.enableWebSockets` | `Boolean` | `true` | Master switch. When `false`, the `HoistWebSocketConfigurer` bean is not registered and no WebSocket endpoint is exposed. Set in `application.groovy`. |

### Soft configuration (AppConfig)

| Config key | Type | Default | Description |
|------------|------|---------|-------------|
| `xhWebSocketConfig` | `json` | `{sendTimeLimitMs: 1000, bufferSizeLimitBytes: 1000000}` | Parameters for the `ConcurrentWebSocketSessionDecorator` wrapping each session. `sendTimeLimitMs` controls the maximum time (in milliseconds) to wait for a send to complete before timing out. `bufferSizeLimitBytes` controls the maximum buffer size for pending outgoing messages. |

The `xhWebSocketConfig` is read by `HoistWebSocketChannel` at channel creation time. Changing
this config affects only newly created channels — existing channels retain their original
settings until they reconnect.

These values are tuned for typical Hoist usage. Increase `bufferSizeLimitBytes` if pushing large
payloads (e.g. full grid datasets), and increase `sendTimeLimitMs` if clients are on slow
connections. If a send exceeds either limit, the `ConcurrentWebSocketSessionDecorator` will close
the session, and the client will need to reconnect.

## Application Implementation

### Enabling WebSocket support

WebSocket support is enabled by default. No application configuration is needed unless you want
to disable it:

```groovy
// In application.groovy — only needed to disable
hoist {
    enableWebSockets = false
}
```

### Registering channels for app-specific updates

The framework handles the WebSocket lifecycle automatically — connection, registration, and
heartbeats require no app code. What applications must implement is the "subscription" layer:
which clients want which updates.

A typical pattern:

1. **Client connects** — The framework assigns a `channelKey` and sends it to the client via the
   `xhRegistrationSuccess` topic.
2. **Client calls an app endpoint** — e.g. `POST /api/subscribeToPositions` with its
   `channelKey` and any filtering parameters.
3. **App service stores the subscription** — Maps channel keys to their requested data streams.
4. **Server pushes updates** — When data changes, the app service calls `pushToChannel()` or
   `pushToChannels()` with the stored keys.
5. **Client disconnects** — The app service listens for `CHANNEL_CLOSED_EVENT` to clean up
   subscriptions, or uses `hasChannel()` to prune stale keys.

```groovy
class PositionPushService extends BaseService {

    def webSocketService

    // Map of channelKey -> subscription details
    private Map<String, Map> subscriptions = new ConcurrentHashMap<>()

    void init() {
        // Clean up when clients disconnect
        subscribe(WebSocketService.CHANNEL_CLOSED_EVENT) { HoistWebSocketChannel channel ->
            subscriptions.remove(channel.key)
        }

        // Timer to push position updates periodically
        createTimer(
            name: 'pushPositionUpdates',
            interval: 5000,
            runFn: this.&pushPositionUpdates
        )
    }

    /** Called by a client endpoint to register interest in position updates. */
    void registerSubscription(String channelKey, Map filters) {
        subscriptions[channelKey] = filters
    }

    private void pushPositionUpdates() {
        subscriptions.each { channelKey, filters ->
            if (!webSocketService.hasChannel(channelKey)) {
                // Prune stale subscription
                subscriptions.remove(channelKey)
                return
            }
            def data = loadPositions(filters)
            webSocketService.pushToChannel(channelKey, 'positionUpdate', data)
        }
    }

    private List loadPositions(Map filters) {
        // ... app-specific data loading
    }
}
```

## Common Patterns

### Broadcasting to all connected clients

Use `pushToAllChannels()` when a single instance triggers a broadcast (e.g. a `primaryOnly`
timer, an admin action, or a webhook callback):

```groovy
// ✅ Broadcast from a primaryOnly timer or single-instance trigger
void onConfigChanged() {
    webSocketService.pushToAllChannels('configRefresh', [timestamp: System.currentTimeMillis()])
}
```

### Broadcasting from a cluster-wide listener

Use `pushToLocalChannels()` when the triggering event already fires on every instance (e.g. a
replicated cache `onChange` listener or a cluster-wide topic subscription). This avoids sending
duplicate messages:

```groovy
// ✅ In a listener that fires on every instance (e.g. replicated cache onChange)
void onCacheChanged() {
    webSocketService.pushToLocalChannels('dataRefresh', refreshData)
}
```

### Pushing to specific users by role

Query `allChannels` to find channels matching a criteria, then push to those keys:

```groovy
void notifyAdmins(String message) {
    def adminKeys = webSocketService.allChannels
        .findAll {
            def user = userService.find(it.apparentUser.username as String)
            user?.hasRole('HOIST_ADMIN')
        }
        .collect { it.key as String }

    webSocketService.pushToChannels(adminKeys, 'adminAlert', [message: message])
}
```

### Responding to incoming client messages

While uncommon, servers can receive messages from clients. Subscribe to `MSG_RECEIVED_EVENT`:

```groovy
void init() {
    subscribe(WebSocketService.MSG_RECEIVED_EVENT) { Map event ->
        def channel = event.channel as HoistWebSocketChannel,
            topic = event.topic as String,
            data = event.data

        if (topic == 'clientHeartbeat') {
            logDebug("Client ${channel.apparentUsername} sent heartbeat")
        }
    }
}
```

### Checking channel liveness before expensive operations

```groovy
void pushExpensiveReport(String channelKey) {
    // Check first to avoid computing data for a disconnected client
    if (!webSocketService.hasChannel(channelKey)) {
        logDebug("Channel $channelKey no longer connected, skipping report generation")
        return
    }
    def report = generateExpensiveReport()
    webSocketService.pushToChannel(channelKey, 'report', report)
}
```

## Client Integration

On the client side, hoist-react manages the WebSocket connection through its `WebSocketService`.
The integration flow:

1. **Connection** — The hoist-react client opens a WebSocket to `ws[s]://{host}/xhWebSocket`
   with query parameters including `appVersion`, `appBuild`, `loadId`, `tabId`, and
   `clientAppCode`.
2. **Registration** — The server sends an `xhRegistrationSuccess` message containing the
   `channelKey`. The client stores this key.
3. **Heartbeat** — The client periodically sends `{topic: 'xhHeartbeat'}` messages. The server
   replies with `{topic: 'xhHeartbeat', data: 'pong'}`.
4. **Push messages** — Incoming messages are dispatched by `topic` to registered handlers in the
   client app. Application code subscribes to specific topics to receive updates.
5. **Reconnection** — If the connection drops, hoist-react automatically reconnects and
   re-registers, obtaining a new `channelKey`. Application services must account for key changes
   by re-registering subscriptions after reconnection.

The `EnvironmentService` reports `webSocketsEnabled` in its environment payload, allowing the
client to conditionally enable or disable its WebSocket support based on server configuration.

The Hoist Admin Console provides a "WebSockets" tab (backed by `WebSocketAdminController` and
`ClientAdminController`) that displays all connected channels across the cluster with their
metadata, and supports pushing test messages to individual channels.

## Common Pitfalls

### Using `pushToAllChannels` from a cluster-wide listener

If the triggering event fires on every instance (e.g. a replicated cache change listener, or a
Hazelcast topic subscription), using `pushToAllChannels()` will cause each instance to broadcast
to **all** channels in the cluster — resulting in every client receiving the message N times
(where N is the number of instances).

```groovy
// ❌ Wrong: replicated cache onChange fires on every instance
void onReplicatedCacheChange() {
    webSocketService.pushToAllChannels('refresh', data)   // N instances x all channels = duplicates!
}

// ✅ Correct: use pushToLocalChannels when the trigger fires on every instance
void onReplicatedCacheChange() {
    webSocketService.pushToLocalChannels('refresh', data)  // Each instance pushes to its own clients only
}
```

### Storing channel keys without cleanup

Channel keys become invalid when clients disconnect or reconnect. If your service stores
channel keys (for targeted push), you must clean them up. Failing to do so leads to a growing
collection of stale keys, wasted push attempts, and potential memory leaks.

```groovy
// ❌ Wrong: storing keys without cleanup
void registerSubscription(String channelKey) {
    subscriptions.add(channelKey)
    // Keys never removed — list grows forever
}

// ✅ Correct: subscribe to close events and/or use hasChannel() to prune
void init() {
    subscribe(WebSocketService.CHANNEL_CLOSED_EVENT) { HoistWebSocketChannel channel ->
        subscriptions.remove(channel.key)
    }
}
```

### Accessing HoistWebSocketChannel properties from `getAllChannels()`

`getAllChannels()` returns `Collection<Map>`, not `Collection<HoistWebSocketChannel>`. The maps
contain serialized metadata (with `authUser` and `apparentUser` as Maps serialized from
`HoistUser.formatForJSON()`, not live `HoistUser` objects). Attempting to call channel methods on
these maps will fail.

```groovy
// ❌ Wrong: treating Map results as HoistWebSocketChannel objects
webSocketService.allChannels.each { channel ->
    channel.sendMessage(msg)           // Error! This is a Map, not a channel
    channel.apparentUser.hasRole('MY_ROLE')  // Error! 'apparentUser' is a serialized Map, not a HoistUser
}

// ✅ Correct: use Map keys, look up users when needed
webSocketService.allChannels.each { channelMap ->
    def user = userService.find(channelMap.apparentUser.username as String)
    if (user?.hasRole('MY_ROLE')) {
        webSocketService.pushToChannel(channelMap.key as String, 'alert', data)
    }
}
```

### Sending large payloads without adjusting buffer limits

The `ConcurrentWebSocketSessionDecorator` enforces a buffer size limit (default 1MB via
`xhWebSocketConfig.bufferSizeLimitBytes`). If a message exceeds this limit — or if multiple
messages queue up beyond it — the session is forcibly closed.

```groovy
// ❌ Risky: pushing large datasets without considering buffer limits
webSocketService.pushToChannel(key, 'fullDataset', hugeListOfRecords)

// ✅ Better: push incremental updates or summaries, or increase buffer config
webSocketService.pushToChannel(key, 'dataUpdate', [
    updatedIds: changedRecordIds,
    timestamp: System.currentTimeMillis()
])
```

If large payloads are unavoidable, increase `bufferSizeLimitBytes` in the `xhWebSocketConfig`
AppConfig. Also consider increasing `sendTimeLimitMs` for clients on slower connections.

### Assuming channel keys survive reconnection

When a client reconnects (e.g. after a network interruption or server restart), it receives a
**new** channel key. Any keys stored by the server from the previous session are now invalid.
Design your subscription model so clients re-register after reconnection.

```groovy
// ❌ Wrong: assuming a channelKey is permanent
void onClientStartup(String channelKey) {
    permanentSubscriptions[userId] = channelKey  // Will go stale on reconnect
}

// ✅ Correct: design for re-registration
void onClientSubscribe(String channelKey, String userId) {
    // Replace any previous key for this user
    subscriptionsByUser[userId] = channelKey
}
```
