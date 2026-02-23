# Logging

## Overview

Hoist-core provides a structured logging infrastructure built on top of SLF4J and Logback. Rather
than requiring developers to use raw SLF4J loggers directly, Hoist introduces the `LogSupport` trait
as the primary interface for all application logging. This design provides several advantages:

- **Structured messages** -- Log calls accept varargs of mixed types (strings, maps, exceptions).
  These are serialized into a consistent pipe-delimited format by a custom Logback converter,
  keeping log output human-readable and machine-parseable.
- **Automatic metadata** -- Every log statement automatically captures the current authenticated
  user (when available) and attaches it to the log entry.
- **Timed execution blocks** -- The `withInfo`, `withDebug`, and `withTrace` methods wrap closures
  with automatic timing, logging both completion status and elapsed time.
- **Smart exception handling** -- Exceptions passed to log methods produce a concise summary by
  default. Full stacktraces are only included when the logger is at TRACE level, preventing log
  spam from recurring errors while preserving diagnostic capability.
- **Dynamic log levels** -- Log levels can be changed at runtime through the admin console without
  restarting the server, and those changes propagate across all cluster instances.
- **Built-in log viewer** -- Server logs can be read, searched, and downloaded directly from the
  Hoist admin console.

Every `BaseService` and `BaseController` in a Hoist application automatically implements
`LogSupport`, so the logging API is available everywhere without any additional setup.

---

## Source Files

| File | Location | Role |
|------|----------|------|
| `LogSupport.groovy` | `src/main/groovy/io/xh/hoist/log/` | Core trait providing `logInfo`, `logDebug`, `logWarn`, `logError`, `logTrace`, `withInfo`, `withDebug`, `withTrace` methods |
| `LogSupportMarker.groovy` | `src/main/groovy/io/xh/hoist/log/` | SLF4J `Marker` implementation that carries structured message data through the logging pipeline |
| `LogSupportConverter.groovy` | `src/main/groovy/io/xh/hoist/log/` | Logback `ClassicConverter` that renders `LogSupportMarker` messages into pipe-delimited human-readable output |
| `ClusterInstanceConverter.groovy` | `src/main/groovy/io/xh/hoist/log/` | Logback converter for the `%instance` pattern token; outputs the cluster instance name |
| `SimpleLogger.groovy` | `src/main/groovy/io/xh/hoist/log/` | Concrete `LogSupport` implementation for logging to an arbitrary named logger |
| `LogbackConfig.groovy` | `grails-app/init/io/xh/hoist/` | Programmatic Logback configuration with default appenders, layouts, and log levels |
| `LogLevelService.groovy` | `grails-app/services/io/xh/hoist/log/` | Service managing runtime log level overrides via the `LogLevel` domain class |
| `LogLevel.groovy` | `grails-app/domain/io/xh/hoist/log/` | GORM domain class persisting log level overrides (table `xh_log_level`) |
| `LogReaderService.groovy` | `grails-app/services/io/xh/hoist/log/` | Service providing server-side log file listing, reading, searching, and deletion |
| `LogArchiveService.groovy` | `grails-app/services/io/xh/hoist/log/` | Service for automatic archival and cleanup of old log files into compressed ZIP bundles |
| `LogLevelAdminController.groovy` | `grails-app/controllers/io/xh/hoist/admin/` | REST controller for CRUD operations on `LogLevel` domain objects |
| `LogViewerAdminController.groovy` | `grails-app/controllers/io/xh/hoist/admin/cluster/` | Controller exposing log file listing, reading, downloading, deletion, and archival endpoints |
| `TrackLoggingService.groovy` | `grails-app/services/io/xh/hoist/track/` | Specialized service writing activity tracking entries to dedicated log files (uses `SimpleLogger`) |

---

## Key Classes

### `LogSupport` (Trait)

**File:** `src/main/groovy/io/xh/hoist/log/LogSupport.groovy`

`LogSupport` is a Groovy trait implemented by `BaseService`, `BaseController`, and any other class
that needs structured logging. It provides two families of methods:

#### Direct logging methods

These methods accept varargs of objects that are converted to strings and joined with ` | `:

| Method | Level | Description |
|--------|-------|-------------|
| `logTrace(Object... msgs)` | TRACE | Finest-grained diagnostic output |
| `logDebug(Object... msgs)` | DEBUG | Development-time diagnostics |
| `logInfo(Object... msgs)` | INFO | Standard operational messages |
| `logWarn(Object... msgs)` | WARN | Warning conditions |
| `logError(Object... msgs)` | ERROR | Error conditions |

All methods check if the corresponding level is enabled before performing any work, so there is no
cost to leaving log statements in production code at a disabled level.

When an exception is passed as a message argument, `LogSupport` formats it as a concise summary
(via `ExceptionHandler.summaryTextForThrowable`). A full stacktrace is only appended when the
logger's effective level is TRACE.

#### Timed execution methods

These methods wrap a closure, automatically logging elapsed time and completion status:

| Method | Level | Description |
|--------|-------|-------------|
| `withTrace(Object msgs, Closure c)` | TRACE | Time a block at TRACE level |
| `withDebug(Object msgs, Closure c)` | DEBUG | Time a block at DEBUG level |
| `withInfo(Object msgs, Closure c)` | INFO | Time a block at INFO level |

Each `withXxx` method:

1. Optionally logs a `started` message at the **same** level as the `withXxx` method, if a finer
   level is also enabled. Specifically, `withInfo` logs its `started` message at INFO level when
   DEBUG is enabled; `withDebug` logs its start at DEBUG level when TRACE is enabled. For
   `withTrace`, the `started` message is always logged (since TRACE is already the finest level).
2. Executes the closure and measures elapsed time.
3. Logs a `completed` message with the elapsed time (e.g., `completed | 342ms`).
4. If the closure throws, logs a `failed` message with the elapsed time, then re-throws the
   exception.
5. Returns the closure's return value, preserving type via generics.

#### The `instanceLog` property

By default, `getInstanceLog()` returns a logger named after the implementing class
(`LoggerFactory.getLogger(this.class)`). Both `BaseService` and `BaseController` override this with
a cached `Logger` field for performance:

```groovy
private final Logger _log = LoggerFactory.getLogger(this.class)
Logger getInstanceLog() { _log }
```

You can override `getInstanceLog()` in your own class to direct log output to a different logger.

### `LogSupportMarker` and `LogSupportConverter`

These two classes form the rendering pipeline for `LogSupport` messages:

1. **`LogSupportMarker`** -- A minimal SLF4J `Marker` implementation that carries a `List messages`
   (which may contain strings, maps, lists, and throwables) along with a reference to the originating
   `Logger`. This marker is attached to every log event produced by `LogSupport`.

2. **`LogSupportConverter`** -- Registered as the Logback converter for `%m`, `%msg`, and `%message`
   pattern tokens. When it encounters a `LogSupportMarker`, it renders the structured message data
   using these conventions:
   - Arguments are pipe-delimited (` | `).
   - Map keys starting with `_` are treated as metadata -- only their values are printed
     (e.g., `[_status: 'completed']` renders as `completed`).
   - The special key `_elapsedMs` is rendered with an `ms` suffix
     (e.g., `[_elapsedMs: 342]` renders as `342ms`).
   - Throwables are rendered as concise summaries. If the logger is at TRACE level, a full
     stacktrace is appended.

### `SimpleLogger`

**File:** `src/main/groovy/io/xh/hoist/log/SimpleLogger.groovy`

A concrete `LogSupport` implementation for logging to a named logger that is not tied to a
particular class. Used by `TrackLoggingService` to write to a dedicated tracking log:

```groovy
SimpleLogger orderedLog = new SimpleLogger('io.xh.hoist.track.TrackLoggingService.Log')
orderedLog.logInfo('Some tracking message')
```

### `LogLevelService`

**File:** `grails-app/services/io/xh/hoist/log/LogLevelService.groovy`

Manages runtime log level adjustments persisted via the `LogLevel` domain class. Key behaviors:

- **Timer-driven recalculation** -- Runs `calculateAdjustments()` every 30 minutes to synchronize
  Logback logger levels with the database.
- **Immediate propagation** -- When a `LogLevel` record is created, updated, or deleted, the GORM
  callbacks trigger `noteLogLevelChanged()`, which calls `calculateAdjustments()` on *all cluster
  instances* via `ClusterUtils.runOnAllInstances`.
- **Default level tracking** -- Before applying an override, the service records the logger's
  original (configured) level. When an override is removed, the logger is restored to its default.
- **Inherit support** -- Setting a level to `'Inherit'` removes the explicit level on the logger,
  causing it to inherit from its parent in the logger hierarchy.

### `LogReaderService`

**File:** `grails-app/services/io/xh/hoist/log/LogReaderService.groovy`

Provides server-side log file access for the admin console:

- **`listFiles()`** -- Returns metadata (filename, size, last modified) for all `.log` files
  under the log root directory.
- **`getFile(filename, startLine, maxLines, pattern, caseSensitive)`** -- Reads log content with
  optional line offset, line limit, and regex pattern filtering. Supports both forward reading
  (from a start line) and tail reading (most recent lines). Enforced by a configurable timeout
  (`xhLogSearchTimeoutMs`, default 5000ms) to prevent runaway queries.
- **`get(filename)`** -- Returns the raw `File` object for direct download.
- **`deleteFiles(filenames)`** -- Deletes specified log files.

### `LogArchiveService`

**File:** `grails-app/services/io/xh/hoist/log/LogArchiveService.groovy`

Automated log file cleanup and archival:

- Runs daily on a timer.
- Identifies rolled log files older than `archiveAfterDays` (from `xhLogArchiveConfig`).
- Groups old files by category (extracted from the filename prefix) and month.
- Compresses them into ZIP archives in a configurable subdirectory (default `archive/`).
- Deletes the original files after successful archival.

### `LogbackConfig`

**File:** `grails-app/init/io/xh/hoist/LogbackConfig.groovy`

Programmatic Logback configuration that replaces the traditional `logback.groovy` DSL. This class:

1. **Registers custom converters** -- `LogSupportConverter` for `%m`/`%msg`/`%message` and
   `ClusterInstanceConverter` for `%instance`.
2. **Creates default appenders** -- A console appender (`stdout`), a daily rolling file appender
   for the main application log, and dedicated daily logs for activity tracking and monitoring.
   All file-based appenders include the cluster instance name in their filename
   (e.g., `myapp-inst1-app.log`, `myapp-inst1-track.log`, `myapp-inst1-monitor.log`), ensuring
   each instance writes to its own files in multi-instance deployments.
   The tracking and monitoring loggers are configured with `additivity: false`, preventing their
   entries from duplicating into the main application log. The tracking log uses a minimal `%m%n`
   layout (no timestamps or metadata in the log line itself, since tracking entries carry their own
   timestamps and are buffered for correct ordering by `TrackLoggingService`).
3. **Sets default log levels** -- ROOT at WARN, `io.xh` and the application package at INFO,
   with select noisy third-party packages (Spring, Hibernate, LDAP, Hazelcast CP) at ERROR,
   and the Grails `StackTrace` logger turned OFF.
4. **Supports customization** -- Applications create a subclass in their own `Config` directory
   and override `configureLogging()`. Helper methods are available:
   - `dailyLog(name, layout, subdir)` -- Creates a daily rolling file appender.
   - `monthlyLog(name, layout, subdir)` -- Creates a monthly rolling file appender.
   - `consoleAppender(name, layout)` -- Creates a console appender.
   - `logger(name, level, appenderNames, additivity)` -- Configures a logger.
   - `root(level, appenderNames)` -- Configures the root logger.
5. **Determines log directory** -- Defaults to `[catalina.base]/logs/[appCode]-logs`. Can be
   overridden via the `-Dio.xh.hoist.log.path` JVM property.
6. **Includes fallback handling** -- If `configureLogging()` throws, the system resets and falls
   back to basic console-only logging.

Default layout patterns include the cluster instance name (via `%instance`), abbreviated class name,
level, and the structured message:

```
# stdout layout
%d{yyyy-MM-dd HH:mm:ss.SSS} | %instance | %c{0} [%p] | %m%n

# daily log layout (no date prefix since files are date-partitioned)
%d{HH:mm:ss.SSS} | %instance | %c{0} [%p] | %m%n
```

---

## Configuration

### AppConfig Keys

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `xhEnableLogViewer` | `bool` | `true` | Enables/disables the log viewer in the Hoist Admin console and its server-side endpoints |
| `xhLogArchiveConfig` | `json` | `{archiveAfterDays: 30, archiveFolder: "archive"}` | Controls automatic log archival -- how many days to retain before archiving, and the subdirectory name for archives |
| `xhLogSearchTimeoutMs` | `long` | `5000` | Maximum time (in milliseconds) allowed for a log search query before it is aborted. Not a registered AppConfig by default -- read via `configService.getLong()` with a fallback |

### Dynamic Log Level Changes

Log levels are managed at runtime through the `LogLevel` domain class (stored in the `xh_log_level`
database table). The admin console provides a UI for this under the "Log Levels" tab. Changes are:

1. Persisted to the database via `LogLevelAdminController`.
2. Applied immediately on the local instance by calling `logLevelService.calculateAdjustments()`.
3. Propagated to all cluster instances via `ClusterUtils.runOnAllInstances`.
4. Periodically re-applied every 30 minutes as a safety net.

Supported levels: `Trace`, `Debug`, `Info`, `Warn`, `Error`, `Inherit` (which removes the
explicit level and defers to the parent logger), and `Off`.

### Log Root Path

The directory where log files are written is determined by `LogbackConfig.getLogRootPath()`:

1. **JVM property** `-Dio.xh.hoist.log.path` -- highest priority, if set.
2. **Default** -- `[catalina.base]/logs/[appCode]-logs` when running under Tomcat.
3. **Local dev fallback** -- When `catalina.base` is not set (e.g., running via `grails run-app`),
   logs are written to `[appCode]-logs` relative to the working directory.

---

## Common Patterns

### Basic logging in a service

```groovy
class OrderService extends BaseService {

    void processOrder(Map order) {
        logInfo('Processing order', [orderId: order.id, customer: order.customer])
        // ...
        logDebug('Validated order details', order.id)
    }
}
```

Output:
```
14:32:01.123 | inst1 | OrderService [INFO] | jdoe | Processing order | orderId=ORD-123 | customer=Acme Corp
14:32:01.130 | inst1 | OrderService [DEBUG] | jdoe | Validated order details | ORD-123
```

### Timed execution blocks

```groovy
class DataSyncService extends BaseService {

    void syncAll() {
        withInfo('Syncing external data') {
            fetchRemoteRecords()
            updateLocalDatabase()
        }
    }
}
```

Output (at INFO level):
```
14:32:05.500 | inst1 | DataSyncService [INFO] | jdoe | Syncing external data | completed | 2340ms
```

Output (at DEBUG level -- additional `started` message appears):
```
14:32:05.500 | inst1 | DataSyncService [INFO] | jdoe | Syncing external data | started
14:32:07.840 | inst1 | DataSyncService [INFO] | jdoe | Syncing external data | completed | 2340ms
```

### Passing structured data

```groovy
withDebug([_msg: 'Reading log file', _filename: filename, startLine: startLine, maxLines: maxLines]) {
    doRead(filename, startLine, maxLines, pattern, caseSensitive)
}
```

Map keys starting with `_` have only their values printed (no `key=` prefix). Other keys are
printed as `key=value`:

```
14:32:10.100 | inst1 | LogReaderService [DEBUG] | jdoe | Reading log file | app.log | startLine=1 | maxLines=500 | completed | 87ms
```

### Logging exceptions

```groovy
try {
    riskyOperation()
} catch (Exception e) {
    logError('Failed to complete operation', [orderId: 'ORD-123'], e)
}
```

At INFO/DEBUG level -- concise summary:
```
14:32:15.200 | inst1 | OrderService [ERROR] | jdoe | Failed to complete operation | orderId=ORD-123 | java.net.ConnectException: Connection refused
```

At TRACE level -- full stacktrace is appended:
```
14:32:15.200 | inst1 | OrderService [ERROR] | jdoe | Failed to complete operation | orderId=ORD-123 | java.net.ConnectException: Connection refused
           at java.net.PlainSocketImpl.socketConnect(Native Method)
           at java.net.AbstractPlainSocketImpl.doConnect(...)
           ...
```

### Using `SimpleLogger` for a dedicated log

```groovy
class ReportService extends BaseService {

    SimpleLogger auditLog = new SimpleLogger('com.myapp.audit')

    void generateReport(String reportName) {
        auditLog.logInfo('Report generated', [report: reportName, user: username])
    }
}
```

### Customizing `LogbackConfig`

Create a subclass in your application's config directory:

```groovy
// grails-app/init/com/myapp/MyAppLogbackConfig.groovy
package com.myapp

import io.xh.hoist.LogbackConfig
import static ch.qos.logback.classic.Level.*

class MyAppLogbackConfig extends LogbackConfig {

    protected void configureLogging() {
        // Start with the Hoist defaults
        super.configureLogging()

        // Raise a chatty package to ERROR
        logger('com.mycompany.chattylib', ERROR)

        // Create a dedicated monthly log for order tracking
        monthlyLog('order-tracking')
        logger('com.mycompany.orders', INFO, ['order-tracking'])
    }
}
```

### Custom layouts for structured logging

The `dailyLog()`, `monthlyLog()`, and `consoleAppender()` helper methods all accept a `layout`
parameter that can be either a pattern **String** (the common case) or a **Closure** that returns a
Logback `Layout` object. This allows applications to produce structured output formats such as JSON
for integration with log aggregation tools (e.g., Splunk, ELK, Datadog).

To set up a JSON-formatted log, add the `logback-json-classic` and `logback-jackson` dependencies
(or alternatively `logstash-logback-encoder`) to your `build.gradle`, then pass a layout Closure
to a log appender:

```groovy
// build.gradle
dependencies {
    implementation 'ch.qos.logback.contrib:logback-json-classic:0.1.5'
    implementation 'ch.qos.logback.contrib:logback-jackson:0.1.5'
}
```

```groovy
// grails-app/init/com/myapp/MyAppLogbackConfig.groovy
import ch.qos.logback.contrib.json.classic.JsonLayout
import ch.qos.logback.contrib.jackson.JacksonJsonFormatter

class MyAppLogbackConfig extends LogbackConfig {

    protected void configureLogging() {
        super.configureLogging()

        // Define a Closure that produces a JsonLayout
        def jsonLayout = {
            def ret = new JsonLayout()
            ret.jsonFormatter = new JacksonJsonFormatter()
            ret.jsonFormatter.prettyPrint = false
            ret.timestampFormat = 'yyyy-MM-dd HH:mm:ss.SSS'
            return ret
        }

        // Create a dedicated daily log using the JSON layout
        dailyLog('json-audit', jsonLayout)
        logger('com.mycompany.audit', INFO, ['json-audit'])
    }
}
```

The Closure is called by `createEncoder()` at configuration time. The returned `Layout` is wrapped
in a `LayoutWrappingEncoder` and attached to the appender. Note that `LogSupportConverter`'s
pipe-delimited rendering only applies to pattern-based layouts using `%m` — JSON layouts will
receive the raw SLF4J message and marker data directly.

---

## Client Integration

The Hoist admin console (provided by the `hoist-react` client library) includes a **Log Viewer**
tab and a **Log Levels** tab. These tools communicate with the server via the following controllers:

### Log Viewer (`LogViewerAdminController`)

- **List files** -- Calls `logReaderService.listFiles()` to display all `.log` files with their
  sizes and last-modified timestamps.
- **View file contents** -- Calls `logReaderService.getFile()` to fetch and display log file
  contents with support for tail view, forward pagination, and regex pattern searching.
- **Download** -- Calls `logReaderService.get()` to stream the raw log file.
- **Delete** -- Calls `logReaderService.deleteFiles()` to remove selected files.
- **Archive** -- Calls `logArchiveService.archiveLogs()` to trigger immediate archival.

All operations are **cluster-aware**: the admin UI can target any specific cluster instance via
the `instance` parameter, which is routed through `ClusterUtils.runOnInstance` /
`ClusterUtils.runOnInstanceAsJson`.

Access requires the `HOIST_ADMIN_READER` role (read operations) or `HOIST_ADMIN` role (delete and
archive operations).

### Log Levels (`LogLevelAdminController`)

- Standard REST CRUD for `LogLevel` domain objects.
- After every create, update, or delete, calls `logLevelService.calculateAdjustments()` to apply
  changes immediately on the local instance. Cluster-wide propagation is handled separately by GORM
  lifecycle callbacks on the `LogLevel` domain class, which call `logLevelService.noteLogLevelChanged()`
  to trigger `calculateAdjustments()` on all instances via `ClusterUtils.runOnAllInstances`.
- Provides lookup data including all valid levels (`None`, `Trace`, `Debug`, `Info`, `Warn`,
  `Error`, `Inherit`, `Off`).
- Access requires the `HOIST_ADMIN_READER` role.

### Toggling the Log Viewer

Set `xhEnableLogViewer` to `false` to disable the log viewer UI and its server-side endpoints.

---

## Common Pitfalls

### Avoid using raw SLF4J loggers

Every `BaseService` and `BaseController` already implements `LogSupport`. Using raw SLF4J loggers
bypasses Hoist's structured formatting, automatic user metadata, and the admin console's ability
to manage levels dynamically.

```groovy
// ❌ Raw SLF4J -- loses structured formatting and user metadata
import org.slf4j.LoggerFactory
def log = LoggerFactory.getLogger(this.class)
log.info("Processing order ${orderId}")

// ✅ Use LogSupport methods
logInfo('Processing order', orderId)
```

### Avoid string interpolation for expensive arguments

While `LogSupport` methods already guard with `isXxxEnabled()` checks, avoid building expensive
strings or objects at the call site when logging at levels that are typically disabled:

```groovy
// ❌ The list is built even if DEBUG is disabled, wasting CPU
logDebug("Full state dump: ${expensiveStateReport()}")

// ✅ Pass as separate arguments -- they are only serialized if the level is enabled
logDebug('Full state dump', expensiveStateReport())
```

Note: even with the second form, the method argument `expensiveStateReport()` is evaluated before
`logDebug` is called (this is standard JVM behavior). For truly expensive computations, guard
explicitly:

```groovy
// ✅ Best for expensive computations
if (instanceLog.debugEnabled) {
    logDebug('Full state dump', expensiveStateReport())
}
```

### Do not forget `withInfo` returns the closure result

The `withXxx` methods return whatever the wrapped closure returns. Use this to keep code concise:

```groovy
// ❌ Ignoring the return value, duplicating the variable
withInfo('Loading portfolio') {
    portfolio = loadPortfolio(id)
}
return portfolio

// ✅ Use the return value directly
return withInfo('Loading portfolio') {
    loadPortfolio(id)
}
```

### Avoid logging sensitive data

Maps passed to log methods are printed as `key=value` pairs. Be careful not to include passwords,
tokens, or other secrets:

```groovy
// ❌ Password will appear in logs
logInfo('User login', [username: user.name, password: user.password])

// ✅ Omit sensitive fields
logInfo('User login', [username: user.name])
```

### Do not set levels via Logback config when dynamic levels are in use

If you adjust a logger's level in your `LogbackConfig` subclass and also create a `LogLevel`
database override for the same logger, the database override will win on the next
`calculateAdjustments()` cycle. This can cause confusion. Prefer one approach per logger:

- Use `LogbackConfig` for **static** base levels (third-party libraries, framework packages).
- Use the admin console `LogLevel` UI for **dynamic** runtime adjustments during troubleshooting.

### Be mindful of the `StackTrace` logger

Hoist turns off the Grails built-in `StackTrace` logger by default because it can swamp logs in
production. If you need stacktraces for a specific logger, set that logger to TRACE level instead
-- `LogSupport` will then include full stacktraces for exceptions logged through that logger.

```groovy
// In the admin console Log Levels tab:
// Set io.xh.hoist.mypackage.MyService -> Trace
//
// Now any logError/logWarn calls on MyService will include full stacktraces.
```
