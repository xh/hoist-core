# Changelog

## 18.0-SNAPSHOT - under development

### 🎁 New Features

* Added new `io.xh.hoist.data.filter` package, with basic implementations of data-filtering classes
  and utils mirroring their TypeScript equivalents within Hoist React.
    * Note these new classes are currently incubating, do not yet implement all features of their
      client-side equivalents, and may be subject to near-term API changes.

## 17.3.0 - 2023-09-18

### ⚙️ Technical

* New `ConfigService.setValue()` API supports programmatic updates to existing app configs.

## 17.2.0 - 2023-08-17

### 🎁 New Features

* Lightweight monitoring collection of JDBC connection pool statistics, including counters for
  active vs idle connections. Viewable in Hoist Admin Console for apps on `hoist-react >= 59.0`.

## 17.1.0 - 2023-08-08

### ⚙️ Technical

* Additional improvements to support hot-reloading.

## 17.0.0 - 2023-07-27

This release upgrades Hoist to the latest 6.0.0 version of Grails and upgrades related libraries.
It should be fully compatible with Java 11 and Java 17.

### 🎁 New Features

* This version of Hoist restores the ability to do development-time reloading via the java hotswap
  agent. [See the readme](https://github.com/xh/hoist-core/blob/develop/README.md#hot-reloading) for
  more information.

### ⚙️ Technical

* The implementation of the `LogSupport` trait has been simplified, such that it no longer requires
  an @SLF4J annotation, or `log` property to be provided. Undocumented and problematic methods
  `logXXXInBase` were removed.

### 📚 Libraries

* grails `5.3.2 -> 6.0.0`
* gorm `7.3.2` -> `8.0.0`
* groovy `3.0.9` -> `3.0.11`

## 16.4.4 - 2023-08-03

### 🐞 Bugfixes

* Replace bullet points with hyphens in default `xhAppVersionCheck` config.

## 16.4.3 - 2023-08-02

### 🐞 Bugfixes

* Remove one remaining smart quote to make default notes in default config safer for all DBs.

## 16.4.2 - 2023-07-31

### 🐞 Bugfixes

* Make default notes in default config safer for all DBs by removing smart quotes.

## 16.4.1 - 2023-07-13

### 🐞 Bugfixes

* Make impersonation service more robust for applications with dynamic/lazy user generation.
* Additional validation of parameters to '/userAdmin/users' endpoint.

## 16.4.0 - 2023-07-07

### 🎁 New Features

* Added new `logData` option to `TrackService.track()` - allows applications to request that
  key/value pairs provided within the `data` block of a track statement be logged along with the
  standard output. Client-side support for this feature on a per-call basis added
  in `hoist-react >= 57.1`, can also be defaulted within the `xhActivityTrackingConfig` app config.
* Deprecated config `xhAppVersionCheckEnabled` in favor of object based `xhAppVersionCheck`. Apps
  will migrate the existing value to this new config's `mode` flag. This supports the new
  `forceRefresh` mode introduced in hoist-react v58.

## 16.3.0 - 2023-06-20

### 🎁 New Features

* Added support for saving alert banner presets (requires `hoist-react >= 57.0.0` to use this new
  functionality, but backwards compatible with earlier hoist-react releases).
* Defined new `HOIST_IMPERSONATOR` role to control access to Hoist's user-impersonation feature.
    * This new role is inherited by `HOIST_ADMIN` (the role previously required) by default,
      although applications can override `BaseUserService.getRolesForUser()` to customize.
    * Applications that have already overridden this method will need to re-implement this role
      inheritance, or assign the new role to appropriate users directly.
* Exposed new `BaseUserService.impersonationTargetsForUser()` template method to allow apps to
  customize the list of users that an admin can impersonate.
* Added support for OWASP-encoding user submitted strings to `BaseController` via a
  new `safeEncode()` method and a new `safeEncode` option to `parseRequestJSON()`
  and `parseRequestJSONArray()`.
    * Apps are encouraged to run any user-provided inputs through this method to prevent XSS
      attacks.

## 16.2.0 - 2023-05-26

### 🎁 New Features

* Added new `BaseController` methods `parseRequestJSON()` and `parseRequestJSONArray()`.
    * These methods are the recommended way to parse JSON from a request body - they will use
      Hoist's optimized, Jackson-based `JSONParser`.
* Created new `xhExpectedServerTimeZone` app config, now read at startup to validate that the server
  is running in a particular, application-configured time zone.
    * Default value of `*` will skip validation but log a warning that no zone is configured.
    * If a zone is configured, Hoist will throw a fatal exception if it does not match the zone
      reported by Java.
    * Most applications should ensure that this config and the runtime JVM are set to the same time
      zone as their primary database.
* Added `h2Config` method to `RuntimeConfig` class to give apps the option of starting up with an H2
  in-memory DB. This is intended for projects in their earliest, "just checked out, first run"
  stage, when a developer wants to get started before having set up an external database.
* Updated `AlertBannerService` to append the environment name when creating/updating the `JsonBlob`
  used to persist banner state in a non-production environment. This better supports apps where
  e.g. `Beta` and `Production` environments share a database, but should display distinct banners.
* Added support for the `caseSensitive` flag in log filtering endpoint.

### 🐞 Bugfixes

* Fixed a regression preventing the culling of snapshots in the memory monitoring service.

## 16.1.0 - 2023-04-14

* Enhance MemoryMonitoringService.
    * Produce and use more appropriate usage metric (used/max)
    * Produce GC statistics
    * Support for taking a heap dump

## 16.0.1 - 2023-03-29

### 🐞 Bugfixes

* Fixed a regression with 404 errors being incorrectly handled and not serialized as JSON.

## 16.0.0 - 2023-03-24

### 🎁 New Features

* `EmailService.sendEmail()` now supports the `attachments` argument, for attaching one or more
  files to the email.
* A new `xhActivityTrackingConfig` soft-configuration entry will be automatically created to control
  the behavior of built-in Activity Tracking (via `TrackService`).
    * Most notably, the size of any `data` objects included with track log entries will be
      constrained by this config, primarily to constrain memory usage when querying and serializing
      large numbers of log entries for the Admin Console.
    * Any track requests with data objects exceeding this length will be persisted, but without the
      requested data.

### 💥 Breaking Changes

* Removed support for "local" preferences - any existing prefs previously marked as local will now
  work like all others, with their values persisted on the server.
    * Apps upgrading to this Core release should simultaneously upgrade to Hoist React v56, which
      will automatically post any existing local preference *values* to the server.
    * Alternatively, update client-side code to use browser local storage for persisting user state
      that should remain tightly bound to a particular computer.
    * Update the schema to set `xh_preference` table's `local` column to allow nulls. If this is
      not done, a Hibernate error (`local` column cannot be null) will be thrown when an admin
      tries to add a new preference to the app.
        ```sql
        alter table xh_preference alter column local bit null
        ```
    * Once they are sure no rollback is needed, apps can safely delete the `xh_preference` table's
      `local` column.
      ```sql
      alter table xh_preference drop column local
      ```

* Grails has been updated to `5.3.2`. While this change did not itself introduce any breaking
  changes, applications should update their Grails version within `gradle.properties` to match.

### 🐞 Bugfixes

* Client Error timestamps will now correctly reflect the exact time the error was received on the
  server rather than the time the error was bulk processed by the server.

### 📚 Libraries

* grails `5.2.1 -> 5.3.2`

## 15.0.0 - 2022-12-5

### 🎁 New Features

Version 15 includes changes to support more flexible logging of structured data:

* The bulk of Hoist conventions around log formatting have been moved from `LogSupport` to a new
  log converter -- `LogSupportConverter`. This allows applications to more easily and fully
  customize their log formats by specifying custom converters.
* `LogSupport` should still be the main entry point for most application logging. This class
  provides the support for enhanced meta data-handling as well as some important APIs -
  e.g. `withDebug()` and `withInfo()`.
* Applications are now encouraged to provide `LogSupport` methods with data in `Map` form. Provided
  converters will serialize these maps as appropriate for target logs.
* Hoist's `LogSupportConverter` is intended for easy reading by humans, allows specifying
  keys that should disappear in the final output with an `_` prefix. This is useful for keys that
  are obvious, e.g. `[_status: 'completed', rows: 100]` logs as `'completed' | rows=100`.
* Alternatively, applications may now specify custom converters that preserve all keys and are
  more appropriate for automatic processing (e.g. splunk). An example of such a converter is
  `CustomLogSupportConverter` which can be found in
  the [Toolbox project](https://github.com/xh/toolbox).
* By default, Hoist now also logs the time in millis when a log message occurred.

## 14.4.2 - 2022-11-14

### ⚙️ Technical

* Improved the signatures of `LogSupport` methods `withInfo` (and similar) to pass through the
  return type of their closure argument.

## 14.4.1 - 2022-10-24

### 🐞 Bugfixes

* Allow database connection info to viewed by users with role: `HOIST_ADMIN_READER` and higher.

## 14.4.0 - 2022-10-19

### 🎁 New Features

* The Hoist Admin Console is now accessible in a read-only capacity to users assigned the
  new `HOIST_ADMIN_READER` role.
* The pre-existing `HOIST_ADMIN` role inherits this new role, and is still required to take any
  actions that modify data.
* Requires `hoist-react >= 53.0` for client-side support of this new readonly role.

## 14.3.1 - 2022-10-10

### ⚙️ Technical

* Status monitor now prepends its generated message to any more specific message provided by
  app-level status check code when the result is ERROR, FAIL, or WARN. Previously any app-specific
  messages were overridden entirely.

### 🐞 Bugfixes

* Correct type specified for `notFoundValue` arg in `ConfigService.getLong()` and `getDouble()`
  method signatures.

## 14.3.0 - 2022-09-23

* Excel exports now support per-cell data types and long values for `int` types.

## 14.2.1 - 2022-09-06

### 🐞 Bugfixes

* Fix to minor regression in client error emails.

## 14.2.0 - 2022-08-19

* Activity tracking enhancements. Tracking can now be done without the context of a web request and
  an explicit specification of a username is allowed.

## 14.1.2 - 2022-08-05

### ⚙️ Technical

* Relaxed character limit on subject length for emails sent via `emailService` from `70` to `255`

## 14.1.1 - 2022-08-03

### ⚙️ Technical

* Revert groovy version to `3.0.9` to support java/groovy compilation.

### 📚 Libraries

* groovy `3.0.11 -> 3.0.9`

## 14.1.0 - 2022-07-29

⚠ Note - applications should add `logback.version=1.2.7` as a new line to their `gradle.properties`
file to fix logback on a version that remains compatible with Hoist's Groovy-based configuration.

### ⚙️ Technical

* `PrefService.getClientConfig()` has been optimized to reduce the number of database calls.
  Previously one select was issued per non-local preference when the second-level query cache was
  cold for a given user. Now only a single select is required.
* `DateTimeUtils` app/server timezone conversion utils default to current day/date if called without
  arguments.
* Standard JSON serialization/deserialization of newer Java date classes added with registration of
  the JSR310 module.
* `LogSupport` methods `withInfo`, `withDebug`, and  `withTrace` will now output a pre-work "
  Starting" message whenever logging is at level 'debug' or above. Previously level 'trace' was
  required.
* Additional logging added to `MemoryMonitoringService`.

### 📚 Libraries

* grails `5.1.1 -> 5.2.1`
* groovy `3.0.9 -> 3.0.11`
* gorm `7.1.2 -> 7.3.2`
* hibernate `5.6.3 -> 5.6.10`
* org.grails.plugins:hibernate `7.2.0 -> 7.3.0`
* httpclient `5.1.2` -> `5.1.3`

[Commit Log](https://github.com/xh/hoist-core/compare/v14.0.0..v14.1.0)

## 14.0.0 - 2022-07-12

### 🎁 New Features

* New method on `BaseController` `runAsync` provides support for asynchronous controllers

### 🐞 Bug Fixes

* Fixed exporting to Excel file erroneously coercing certain strings (like "1e10") into numbers.

### 💥 Breaking Changes

* Requires `hoist-react >= 50.0`. Exporting to Excel defaults to using column FieldType.

[Commit Log](https://github.com/xh/hoist-core/compare/v13.2.2..v14.0.0)

## 13.2.2 - 2022-06-14

### 🐞 Bug Fixes

* Fixed a bug with JSON Blob diffing.

[Commit Log](https://github.com/xh/hoist-core/compare/v13.2.1...v13.2.2)

## 13.2.1 - 2022-05-27

### 🐞 Bug Fixes

* Fixed a bug with impersonation not ending cleanly, causing the ex-impersonator's session to break
  upon server restart.
* Fixed a bug in implementation of `clearCachesConfigs`

[Commit Log](https://github.com/xh/hoist-core/compare/v13.2.0...v13.2.1)

## 13.2.0 - 2022-04-28

### 🎁 New Features

* Admin log file listing includes size and last modified date, visible with optional upgrade
  to `hoist-react >= 48.0`.

[Commit Log](https://github.com/xh/hoist-core/compare/v13.1.0...v13.2.0)

## 13.1.0 - 2022-02-03

### ⚙️ Technical

* Support for reporting configuration state of Web Sockets
* New property `Utils.appPackage` for DRY configuration.

### 🐞 Bug Fixes

* Fix to regressions in Excel exports and logging due to changes in Groovy `list()` API.

[Commit Log](https://github.com/xh/hoist-core/compare/v13.0.6...v13.1.0)

## 13.0.6 - 2022-01-13

### ⚙️ Technical

* `LocalDate`s are now serialized in the more fully ISO standard "YYYY-MM-DD" format, rather than
  "YYYYMMDD". Note that this is consistent with similar changes to `LocalDate` serialization in
  Hoist React v46.
* Although this format will be accepted client-side by `hoist-react >= 45.0`, apps that are parsing
  these strings directly on the client may need to be updated accordingly.

### 🐞 Bug Fixes

* Fix to Regressions in JsonBlobService/AlertBannerService

[Commit Log](https://github.com/xh/hoist-core/compare/v13.0.5...v13.0.6)

## 13.0.5 - 2022-01-11

This version includes a major upgrade of several underlying libraries, especially grails (5.1),
spring (5.3), spring-boot (2.6), groovy (3.0), and gradle (7.3). With this version, Hoist can now be
run on Java versions 11 - 17. We have also cleaned up and enhanced some core APIs around Exception
Handling, JSON parsing and configuration.

Please see
the [Grails5 Toolbox update commit](https://github.com/xh/toolbox/commit/2e75cb44f5c600384334406724bb63e3abc98dcc)
for the application-level changes to core configuration files and dependencies.

### 💥 Breaking Changes

* The trait `AsyncSupport` with its single method `asyncTask` has been removed. Use the equivalent
  method `task` from `grails.async.Promises` instead.
* The method `subscribeWithSession` on `BaseService` has been removed. Use `subscribe` instead.
* Application Tomcat Dockerfiles must be updated to use a new `xh-tomcat` base image on JDK 11/17.
* Groovy Language:  `list` methods changed:
    * `push()` now prepends an item to the start of the List. To append to the end, use `add()`.
    * `pop()` now removes the first item from the List. To remove the last item, use `removeLast()`.

### ⚙️ Technical

* This release upgrades the major version of grails from 3.3.9 to 5.1. This major release includes
  the following upgrades of related libraries:
    * spring boot `1.x -> 2.6`
    * groovy `2.4 -> 3.0`
    * gradle `4.10 -> 7.3`
    * gorm `6.1 -> 7.1`
    * hibernate `5.1 -> 5.6`
    * org.grails.plugins:mail `2.0 -> 3.0`
    * apache poi  `3.1` -> `4.1`
* Default application configuration is now better bundled within hoist-core. See new
  classes `ApplicationConfig`, `LogbackConfig`, and `RuntimeConfig`. Please consult the grails docs
  as well as the Toolbox update linked above for more information on required changes to config and
  dependency files.
* Options for hot reloading have changed, as `spring-loaded` is now longer supported for java
  versions > jdk 8. As such, options for hot reloading of individual classes are more limited, and
  may require additional tools such as JRebel. See the grails upgrade guide for more info.
* Applications will be required to add the `@Transactional` or `@ReadOnly` annotations to service
  and controller methods that update data or read data from Hibernate/GORM.
* HttpClient has been upgraded from `4.5 -> 5.1`. Package names have changed, and applications using
  this API (e.g. with `JSONClient`) will need to update their imports statements to reflect the new
  locations @ `org.apache.hc.client5.http` and `org.apache.hc.core5.http`. See Toolbox for examples.
* WebSocket Support has been simplified. To enable WebSockets, simply set the application config
  `hoist.enableWebSockets = true` in `application.groovy`. This can replace the custom annotation /
  enhancement of the Application class used in earlier versions of Hoist.
* Hoist JSON Validation now uses the same Jackson configuration used by `JSONParser`.
* The optional `withHibernate` argument to `Timer` is obsolete and no longer needed.

[Commit Log](https://github.com/xh/hoist-core/compare/v11.0.3...v13.0.5)

## 11.0.3 - 2021-12-10

### 🐞 Bug Fixes

* Fix to Regression in v11 preventing proper display of stacktraces in log.

* [Commit Log](https://github.com/xh/hoist-core/compare/v11.0.2...v13.0.5)

## 11.0.2 - 2021-12-06

### ⚙️ Technical

* Minor tweak to allow nested lists and arrays in `LogSupport` statements. Improved documentation.

[Commit Log](https://github.com/xh/hoist-core/compare/v11.0.1...v11.0.2)

## 11.0.1 - 2021-12-03

### 🎁 New Features

* Enhancement to `LogSupport` to help standardize logging across all Service and Controllers. New
  methods `logInfo`, `logDebug`, `logTrace`, `logWarn`, and `logError` now provide consistent
  formatting of log messages plus log-level aware output of any throwables passed to these methods.
  See LogSupport for more info.

### 💥 Breaking Changes

* The methods `LogSupport.logErrorCompact` and `LogSupport.logDebugCompact` have been removed. Use
  `logError` and `logDebug` instead, passing your `Throwable` as the last argument to these methods.

### 🐞 Bug Fixes

* The `lastUpdatedBy` column found in various Admin grid now tracks the authenticated user's
  username, indicating if an update was made while impersonating a user.
* Fix to bug causing 'Edge' browser to be incorrectly identified.

[Commit Log](https://github.com/xh/hoist-core/compare/v10.1.0...v11.0.1)

## 10.1.0 - 2021-11-03

### 🎁 New Features

* New Admin endpoint to output environment variables and JVM system properties.
    * Take (optional) update to `hoist-react >= 44.1.0` for corresponding Hoist Admin Console UI.

[Commit Log](https://github.com/xh/hoist-core/compare/v10.0.0...v10.1.0)

## 10.0.0 - 2021-10-26

⚠ NOTE - apps *must* update to `hoist-react >= 44.0.0` when taking this hoist-core update.

### 🎁 New Features

* Log Levels now include information on when the custom config was last updated and by whom. Note
  required database modifications in Breaking Changes below.
* Client Error messages are now saved and sent in bulk on a timer. This allows Hoist to bundle
  multiple error reports into a single alert email and generally improves how a potential storm of
  error reports is handled.
* Improved `JsonBlob` editing now supports setting null values for relevant fields.

### 💥 Breaking Changes

* Update required to `hoist-react >= 44.0.0` due to changes in `JsonBlobService` APIs and the
  addition of new, dedicated endpoints for Alert Banner management.
* Public methods on `JsonBlobService` have been updated - input parameters have changed in some
  cases, and they now return `JsonBlob` instances (instead of pre-formatted Maps).
* Two new columns should be added to the `xh_log_level` table in your app's database: a datetime
  column and a nullable varchar(50) column. Review and run the SQL below, or an equivalent suitable
  for your app's database. (Note that both columns are marked as nullable to allow the schema change
  to be applied to a database in advance of the upgraded deployment.)

  ```sql
  ALTER TABLE `xh_log_level` ADD `last_updated` DATETIME NULL;
  ALTER TABLE `xh_log_level` ADD`last_updated_by` VARCHAR(50) NULL;
  ```

### ⚙️ Technical

* Dedicated admin endpoints added for Alert Banner management, backed by a new `AlertBannerService`.

[Commit Log](https://github.com/xh/hoist-core/compare/v9.4.0...v10.0.0)

## 9.4.0 - 2021-10-15

### 🎁 New Features

* Log Viewer now supports downloading log files.

[Commit Log](https://github.com/xh/hoist-core/compare/v9.3.1...v9.4.0)

### ⚙️ Technical

* Applications will no longer default to "development" environment in server deployments. A
  recognized environment must be explicitly provided.

## 9.3.2 - 2021-10-01

* `EmailService` now requires an override or filter config before sending any mails in local
  development mode.
* `ClientErrorEmailService` now relays any client URL captured with the error.

[Commit Log](https://github.com/xh/hoist-core/compare/v9.3.1...v9.3.2)

## 9.3.1 - 2021-08-20

* Bootstrap new `xhSizingMode` core preference.

[Commit Log](https://github.com/xh/hoist-core/compare/v9.3.0...v9.3.1)

## 9.3.0 - 2021-08-11

### 🎁 New Features

* Excel cell styles with grouped colors are now cached for re-use, avoiding previously common file
  error that limits Excel tables to 64,000 total styles.
* Client error reports now include the full URL for additional troubleshooting context.
    * ⚠ NOTE - this requires a new, nullable varchar(500) column be added to the xh_client_error
      table in your app's configuration database. Review and run the following SQL, or an equivalent
      suitable for the particular database you are using:

      ```sql
      ALTER TABLE `xh_client_error` ADD COLUMN `url` VARCHAR(500) NULL;
      ```

[Commit Log](https://github.com/xh/hoist-core/compare/v9.2.3...v9.3.0)

## 9.2.3 - 2021-06-24

### ⚙️ Technical

* Parsing of `AppEnvironment` from a string provided via instance config / JVM opts is now
  case-insensitive.

[Commit Log](https://github.com/xh/hoist-core/compare/v9.2.2...v9.2.3)

## 9.2.2 - 2021-06-07

### ⚙️ Technical

* Replaced obsolete jcenter dependency (see https://blog.gradle.org/jcenter-shutdown).

[Commit Log](https://github.com/xh/hoist-core/compare/v9.2.1...v9.2.2)

## 9.2.1 - 2021-04-14

### 🐞 Bug Fixes

* `GridExportImplService` now handles Excel table exports containing no data rows. Previously, the
  Excel file required repair, during which all table and column header formatting was lost.
* Status Monitors no longer evaluate metric-based thresholds if an app-level check implementation
  has already set marked the result with a `FAIL` or `INACTIVE` status, allowing an app to fail or
  dynamically disable a check regardless of its metric.
* Fix incorrect formatting pattern strings on `DateTimeUtils`.

[Commit Log](https://github.com/xh/hoist-core/compare/v9.2.0...v9.2.1)

## 9.2.0 - 2021-03-25

### 🐞 Bug Fixes

* Restore JSON Serialization of `NaN` and `Infinity` as `null`. This had long been the standard
  Hoist JSON serialization for `Double`s and `Float`s but was regressed in v7.0 with the move to
  Jackson-based JSON serialization.

[Commit Log](https://github.com/xh/hoist-core/compare/v9.1.1...v9.2.0)

## 9.1.1 - 2021-01-27

### ⚙️ Technical

* Improvements to the tracking / logging of admin impersonation sessions.

[Commit Log](https://github.com/xh/hoist-core/compare/v9.1.0...v9.1.1)

## 9.1.0 - 2020-12-22

### 🎁 New Features

* Built-in logging utils `withDebug`, `withInfo`, `compactErrorLog` and `compactDebugLog` will log
  username when called in the context of a user request.
* New method `IdentityService.getUsername()` for efficient access to username when no additional
  details about current user are needed.

### ⚙️ Technical

* Improve consistency of exception descriptions in logs.
* Remove repeated exception descriptions in logs: `withDebug` and `withInfo` will no longer print
  exception details.
* TrackService will now log to a dedicated daily log file.

[Commit Log](https://github.com/xh/hoist-core/compare/v9.0.0...v9.1.0)

## 9.0.0 - 2020-12-17

### 💥 Breaking Changes

* `LogSupport` API enhancements:
    * `logErrorCompact()` and `logDebugCompact()` now only show stacktraces on `TRACE`
    * `withInfo()` and `withDebug()` now log only once _after_ execution has completed. Raising the
      log level of the relevant class or package to `TRACE` will cause these utils to also log a
      line
      _before_ execution, as they did before. (As always, log levels can be adjusted dynamically at
      runtime via the Admin Console.)
    * The upgrade to these two utils mean that they **completely replace** `withShortInfo()` and
      `withShortDebug()`, which have both been **removed** as part of this change.
    * Additional stacktraces have been removed from default logging.

### ⚙️ Technical

* `RoutineException`s are now returned with HttpStatus `400` to client, rather than `500`

[Commit Log](https://github.com/xh/hoist-core/compare/v8.7.3...v9.0.0)

## 8.7.3 - 2020-12-15

* Default exception logging in `ExceptionRender` will no longer include stacktraces, but will
  instead use `LogSupport.logErrorCompact()`. To see stacktraces for any given logger, set the
  logging level to `DEBUG`.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.7.2...v8.7.3)

## 8.7.2 - 2020-12-15

### 🐞 Bug Fixes

* Fixed bug preventing cleanup of MemoryMonitoringService snapshots.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.7.1...v8.7.2)

## 8.7.1 - 2020-12-11

### ⚙️ Technical

* Minor enhancements to `JsonBlobService` API.

### 📚 Libraries

* org.apache.httpcomponents:httpclient `4.5.6 -> 4.5.13`

[Commit Log](https://github.com/xh/hoist-core/compare/v8.7.0...v8.7.1)

## 8.7.0 - 2020-12-01

### 🎁 New Features

* Added new `MemoryMonitoringService` to sample and return simple statistics on heap (memory) usage
  from the JVM runtime. Stores a rolling, in-memory history of snapshots on a configurable interval.

### 🔒 Security

* HTML-encode certain user-provided params to XhController endpoints (e.g. track, clientErrors,
  feedback) to sanitize before storing / emailing.

### ⚙️ Technical

* Removed verbose stacktraces appended to the primary app log by the built-in Grails 'StackTrace'
  logger. This logger has now been set to level *OFF* by default. To re-enable these stacktraces,
  raise the log level of this logger in either logback.groovy or dynamically at runtime in the Hoist
  Admin Console.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.6.1...v8.7.0)

## 8.6.1 - 2020-10-28

* `JsonBlobService` - complete support for metadata with additional `meta` property. Requires an
  additional column on blob table, e.g:

  ```sql
  alter table xh_json_blob add meta varchar(max) go
  ```
* Introduce new `AppEnvironment.TEST` enumeration value.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.6.0...v8.6.1)

## 8.6.0 - 2020-10-25

* `JsonBlobService`: Enhancements to archiving, new columns and new unique key constraint.
    - Apps will need to modify the `xh_json_blob` table with new `archived_date` column and related
      unique constraint. SAMPLE migration SQL below:

      ```sql
      alter table xh_json_blob add archived_date bigint not null go
      alter table xh_json_blob drop column archived go
      alter table xh_json_blob add constraint idx_xh_json_blob_unique_key unique (archived_date, type, owner, name)
      ```

    - Apps should update to `hoist-react >= 36.6.0`.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.5.0...v8.6.0)

## 8.5.0 - 2020-10-07

* `JsonBlobService`: Use more scalable token-based access; support archiving. Requires additional
  columns on blob table, e.g:

  ```sql
  alter table xh_json_blob add token varchar(255) not null go
  alter table xh_json_blob add archived boolean default false go
  ```

  Note that the `archived` column is dropped in subsequent versions, and thus need not be added
  unless you are using 8.5.0 specifically.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.4.0...v8.5.0)

## 8.4.0 - 2020-09-25

* `JsonBlobService`: Security enhancements and finalization of API.
* Server Support for Bulk editing of Configs and Preferences.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.3.0...v8.4.0)

## 8.3.0 - 2020-09-21

⚠ NOTE - apps should update to `hoist-react >= 36.1.0` when taking this hoist-core update. This is
required to support the updates to Admin Activity and Client Error tracking described below.

### 🎁 New Features

* Adds support for storing and retrieving `JsonBlob`s - chunks of arbitrary JSON data used by the
  corresponding `JsonBlobService` introduced in hoist-react v36.1.0.

### 🐞 Bug Fixes

* Improved time zone handling in the Admin Console "Activity Tracking" and "Client Errors" tabs.
    * Users will now see consistent bucketing of activity into an "App Day" that corresponds to the
      LocalDate when the event occurred in the application's timezone.
    * This day will be reported consistently regardless of the time zones of the local browser or
      deployment server.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.2.0...v8.3.0)

## 8.2.0 - 2020-09-04

### 🎁 New Features

* Add new `RoutineRuntimeException`

### 🐞 Bug Fixes

* Pref and Config Differ now record the admin user applying any changes via these tools.
* Fix bug with monitoring when monitor script times out.

### ⚙️ Technical

* Specify default DB indices on a small number of bundled domain classes.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.1.0...v8.2.0)

## 8.1.0 - 2020-07-16

### 🎁 New Features

* Add support for Preference Diffing in the Hoist React Admin console.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.0.1...v8.1.0)

## 8.0.1 - 2020-06-29

### 🐞 Bug Fixes

* Fix minor regression to reporting of hoist-core version.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.0.0...v8.0.1)

## 8.0.0 - 2020-06-29

### ⚖️ Licensing Change

As of this release, Hoist is [now licensed](LICENSE.md) under the popular and permissive
[Apache 2.0 open source license](https://www.apache.org/licenses/LICENSE-2.0). Previously, Hoist was
"source available" via our public GitHub repository but still covered by a proprietary license.

We are making this change to align Hoist's licensing with our ongoing commitment to openness,
transparency and ease-of-use, and to clarify and emphasize the suitability of Hoist for use within a
wide variety of enterprise software projects. For any questions regarding this change, please
[contact us](https://xh.io/).

### 🎁 New Features

* New support for `appTimeZone` and `serverTimeZone` in `EnvironmentService`.
* New support for eliding long strings: `StringUtils.elide()`.
* New support for the enhanced Admin Activity Tracking tab shipping in hoist-react v35.

[Commit Log](https://github.com/xh/hoist-core/compare/v7.0.1...v8.0.0)

## 7.0.1 - 2020-06-04

### ⚙ Technical

* Improvements to formatting of monitoring and error emails.
* Bootstrap `xhEnableMonitoring` config
* Add Grails Quartz plugin (v2.0.13)

### 🐞 Bug Fixes

* Fixed a regression to TrackService, preventing persisting lists in the `data` property.

[Commit Log](https://github.com/xh/hoist-core/compare/v7.0.0...v7.0.1)

## 7.0.0 - 2020-05-08

### 🎁 New Features

* Exception Handling has been improved in the newly enhanced `exceptionRenderer` bean. This bean
  will catch uncaught exceptions from all Controllers and Timers and has been newly configured to
  limit the logging of unnecessary stack traces.

* New exception classes for `HttpException` and `ExternalHttpException` have been added.

* JSON parsing in Hoist has been reworked to simplify and standardize based on the high-performance
  Jackson library. (https://github.com/FasterXML/jackson). Benchmarking shows a speedup in parsing
  times of 10x to 20x over the `grails.converter.JSON` library currently used by Hoist. In
  particular, this change includes:
    * A new `JSONParser` API in the `io.xh.hoist.json` package that provides JSON parsing of text
      and input streams. This API is designed to be symmetrical with the existing `JSONFormatter.`
    * All core hoist classes now rely on the API above. Of special note are `JSONClient`, and
      `RestController`.
    * Cleanups to the APIs for `JSONClient`, `ConfigService`, and `PrefService`. These methods now
      return java object representations using the standard java `Map` and `List` interfaces rather
      than the confusing `JSONObject`, `JSONArray` and `JSONElement` objects.

### 🎁 Breaking Changes

* The `getJSONObject()`, `getJSONArray()`, and `getJSON()` methods on `ConfigService` and
  `PrefService` have been replaced with `getMap()` and `getList()`.
* The `executeAsJSONObject()` and `executeAsJSONArray()` methods on `JSONClient` have been replaced
  with `executeAsMap()` and `executeAsList()`.
* The method `RestController.preprocessSubmit()` now takes a `Map` as its single input, rather than
  a `JSONObject`.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.7.0...v7.0.0)

## 6.7.0 - 2020-04-22

### 💥 Breaking Changes

* `Timer.delay` now expects either a millisecond value, or a boolean. It no longer will take a
  string/closure and `Timer.delayUnits` has been removed. This has been changed to enhance the
  functionality and make it consistent with its client-side counterpart in hoist-react.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.6.0...v6.7.0)

## 6.6.0 - 2020-03-27

### 🎁 New Features

* New `xhEnableLogViewer` config available to fully disable the log viewer built into the Admin
  console. Intended for scenarios where the UI server logs are not material/helpful, or potentially
  for cases where they are too chatty/large to be effectively viewed in the Admin UI.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.5.0...v6.6.0)

## 6.5.0 - 2020-03-16

### 🎁 New Features

* Added support for setting custom logging layouts. Applications can use this to further customize
  built-in Hoist logging, including changing it to use alternative file formats such as JSON.
* Also includes enhanced documentation and an example of how to configure logging in Hoist.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.4.4...v6.5.0)

## 6.4.4 - 2020-03-05

### 🐞 Bug Fixes

* Fixed issue where attempting to read very large log files would overly stress server processor and
  memory resources. [#115](https://github.com/xh/hoist-core/issues/115)

### ⚙️ Technical

* Add ability to configure WebSocketService resource limits using soft configuration.
* Note intermediate builds 6.4.2/6.4.3 not for use.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.4.1...v6.4.4)

## 6.4.1 - 2020-02-29

### 🐞 Bug Fixes

* Fixed an issue where GORM validation exceptions would trigger MethodNotFoundException

### ⚙️ Technical

* Switch to using [nanoJson](https://github.com/mmastrac/nanojson) for JSON validation, which
  ensures stricter adherence to the JSON spec.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.4.0...v6.4.1)

## 6.4.0 - 2020-01-21

### 🎁 New Features

* Added a new `xhEnableImpersonation` config for enabling or disabling impersonation app-wide. Note
  that this config will be defaulted to false if not yet defined - set to true after upgrade to
  continue supporting impersonation for your application.
* The `xhMonitorConfig` config supports a new property `monitorTimeoutSecs` to control the max
  runtime for any individual monitor check.
* Any `appBuild` tag is now included in the output of `xh/version`, allowing for client-side version
  checking to take the particular build into account when running on a SNAPSHOT.

### ⚙️ Technical

* All exceptions are now rendered as JSON. HTML exception rendering is no longer supported.
* Exceptions in GORM validation will now be treated as routine and will not be logged.
  ([#95](https://github.com/xh/hoist-core/issues/95))
* GORM validation exceptions are now handled by `BaseController` rather than `RestController`, so
  all endpoints will be handled consistently. ([#68](https://github.com/xh/hoist-core/issues/68))

[Commit Log](https://github.com/xh/hoist-core/compare/v6.3.1...v6.4.0)

## 6.3.1 - 2019-11-12

### 🐞 Bug Fixes

* JSON preferences accept any valid `JSONElement` for their value, not just a `JSONObject`.
* Default `TrackLog.severity` to `INFO` vs. non-standard `OK`.
* Bootstrapped `xhEmailSupport` config now properly `clientVisible`.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.3.0...v6.3.1)

## 6.3.0 - 2019-09-04

### 🎁 New Features

* Grid exports to Excel now support setting an export format on a per-cell basis. Requires an
  updated Hoist React build for client-side support, but is backwards compatible with existing API.

### ⚙️ Technical

* `JSONClient` can be constructed without providing a configured `ClosableHttpClient`. A default
  client will be created and used.
* When pointed to a directory, `InstanceConfigUtils` will first check to see if it contains a file
  named `[appCode].yml` and, if so, will load configs from that single file and return. Otherwise,
  individual files within that directory will be loaded as key/value pairs, as they were previously.
  This allows a single `-Dio.xh.hoist.instanceConfigFile` value to be baked into a container build
  and resolve to either single-file or directory-mode configs based on the deployment environment.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.2.0...v6.3.0)

## 6.2.0 - 2019-08-13

### 🎁 New Features

* The `Timer` class has been enhanced to support intervals as low as 500ms. Previously, `Timer` had
  a minimum interval of 2 seconds.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.1.0...v6.2.0)

## 6.1.0 - 2019-07-31

### 🎁 New Features

* **WebSocket support** has been added in the form of `WebSocketService`. The new service maintains
  and provides send/receive functionality to connected Hoist client apps, each associated with a
  unique channel identifier.
    * ⚠ **Note** this change requires that applications specify a new dependency in their
      `build.gradle` file on `compile "org.springframework:spring-websocket"`. If missing, apps will
      throw an exception on startup related to a failure instantiating `WebSocketService`. Apps
      should
      *not* need to make any changes to their own code / services aside from this new dep.
    * This service and its related endpoints integrate with client-side websocket support and admin
      tools added to Hoist React v26.
    * As per the included class-level documentation, applications must update their
      Application.groovy file to expose an endpoint for connections and wire up
      a `HoistWebSocketHandler` to relay connection events to the new service.

### 🐞 Bug Fixes

* Dedicated Jackson JSON serializer added for Groovy `GString` objects - outputs `toString()` as
  expected vs. obscure/unwanted object representation (#87).

[Commit Log](https://github.com/xh/hoist-core/compare/v6.0.2...v6.1.0)

## 6.0.2 - 2019-07-24

### 🐞 Bug Fixes

* Grid exports will no longer fail if any values fail to parse as per the specified data type.
  Previously a single unexpected value could spoil the export - now they will be passed through
  as-is to the generated worksheet.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.0.1...v6.0.2)

## 6.0.1 - 2019-07-19

### 🐞 Bug Fixes

* Ensure JSON is rendered with `charset=UTF-8` vs. an unexpected ISO fallback we started getting
  once we stopped using the built-in Grails JSON converter in favor of rendering the String output
  from Jackson . Fixes issue with unicode characters getting munged in JSON responses.

[Commit Log](https://github.com/xh/hoist-core/compare/v6.0.0...v6.0.1)

## 6.0.0 - 2019-07-10

### 🎁 New Features

* A `RoutineException` interface has been added. Implement this interface to mark any exception that
  is a part of normal operations and should not necessarily be logged on the server as an error.
* The `DataNotAvailableException` has been added. This class implements `RoutineException` and is
  intended to be thrown when requested data is not currently available due to normal, expected
  business conditions (e.g. the business day has just rolled and new data is not yet ready).
* The [Jackson library](https://github.com/FasterXML/jackson) is now used for JSON Serialization.
  This provides a faster, more standardized approach to rendering JSON. Groovy Traits are also no
  longer used for Hoist's Cached JSON support, improving the ability to use this feature with Java.
* Added `/ping` endpoint for trivial server up / connectivity checks.

### 💥 Breaking Changes

* The `cacheJSON()` method on JSONFormat is no longer available for specifying cached JSON
  serialization. Extend the `JSONFormatCached` class instead.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.5.5...v6.0.0)

## 5.5.5 - 2019-07-06

### ⚙️ Technical

* New default pref `xhShowVersionBar`, remove deco'd pref `xhForceEnvironmentFooter`.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.5.4...v5.5.5)

## 5.5.4 - 2019-06-24

### ⚙️ Technical

* New default config + preference definitions added in Bootstrap to support client-side
  AutoRefreshService.
* Memory/processors available to JVM logged at startup.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.5.3...v5.5.4)

## 5.5.3 - 2019-04-16

### ⚙️ Technical

* Throw new `SessionMismatchException` when client provides a `clientUsername` to /xh endpoints that
  does not match the current session user. (The dedicated exception class is new, not the behavior.)

[Commit Log](https://github.com/xh/hoist-core/compare/v5.5.2...v5.5.3)

## 5.5.2 - 2019-03-06

### ⚙️ Technical

* Admin endpoint to run log archiving routine on demand.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.5.1...v5.5.2)

## 5.5.1 - 2019-01-30

### 🐞 Bug Fixes

* Further work to ensure admin log viewer endpoint is completely wrapped in try/catch to avoid
  throwing repeated stack traces if supplied incorrect parameters.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.5.0...v5.5.1)

## 5.5.0 - 2019-01-24

### ⚙️ Technical

* Monitors will now log all activity to a daily dedicated log of the form `[appName]-monitor.log`.
  This behavior can be controlled with the option `writeToMonitorLog` in the `xhMonitorConfig`
  block.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.4.2...v5.5.0)

## 5.4.2 - 2019-01-14

### ⚙️ Technical

* Activity tracking logs are now written to the database by default, even in local development mode.
  An optional `disableTrackLog` instance config value is now available to prevent them from being
  persisted (in any environment).

### 🐞 Bug Fixes

* Corrected auto-defaulted required config for the log file archive directory path.
* Avoid any attempt to evaluate thresholds for Status Monitor results that do not produce a metric.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.4.1...v5.4.2)

## 5.4.1 - 2018-12-31

### 🐞 Bug Fixes

* Track log entries are now written correctly when an admin starts/stops impersonation.
* InstanceConfigUtils will warn via stdout if a config file is not found or cannot be parsed.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.4.0...v5.4.1)

## 5.4.0 - 2018-12-18

### ⚙️ Technical

* Environment information now includes details on the primary database connection, including the
  JDBC connection string, user, and dbCreate setting. Note this additional info is only returned
  when the requesting user is a Hoist Admin (and is intended for display in the admin JS client).
* Additional `AppEnvironment` enums added for UAT and BCP.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.3.1...v5.4.0)

## v5.3.1 - 2018-12-17

### 📚 Libraries

* Grails `3.3.8 -> 3.3.9`
* GORM `6.1.10 -> 6.1.11`
* Apache HttpClient `4.5.3 -> 4.5.6`

[Commit Log](https://github.com/xh/hoist-core/compare/v5.3.0...v5.3.1)

## v5.3.0 - 2018-11-14

### ⚙️ Technical

* AppConfigs and Preferences now serialize their values / defaultValues with appropriate types in
  their formatForJSON serializations. (Previously values were all serialized as strings.) This
  allows for more direct binding with admin editor form controls of the appropriate type, and
  generally centralizes their serialization in a more consistent way.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.2.0...v5.3.0)

## v5.2.0

### ⚙️ Technical

* Processing of uploaded grid data for Excel export modified to handle even larger export sets. ⚠️
  Note requires client-side toolkit updates (>= v16 for Hoist React).

[Commit Log](https://github.com/xh/hoist-core/compare/v5.1.0...v5.2.0)

## v5.1.0

### 🎁 New Features

* `InstanceConfigUtils` can now read bootstrap configuration values from a directory of files, each
  containing a single config, in addition to the previous support for yaml files. This supports
  reading low-level configuration from Docker/Kubernetes configs and secrets, which are mounted to
  the container filesystem as a directory.

### 🐞 Bug Fixes

* An admin client that happens to be polling for a non-existent log file (e.g. one that has just
  been archived) will no longer spam the logs with stack traces.

[Commit Log](https://github.com/xh/hoist-core/compare/v5.0.4...v5.1.0)

## v5.0.4

### 🐞 Bug Fixes

* Avoids the use of (sometimes) reserved SQL word `level` in the `LogLevel` config object. Remapped
  to `log_level` column.

⚠️ Note that this will require a schema update if Grails is not configured to do so automatically,
and will result in existing overrides having a null level (which is supported and means "no
override").

[Commit Log](https://github.com/xh/hoist-core/compare/release-5.0.3...v5.0.4)

## v5.0.3

### 💥 Breaking Changes

**This release includes several core API changes to how users and their roles (permissions) are
loaded.** (Note that the breaking changes below will typically be handled by updates to a custom
enterprise plugin and not require individual app changes.)

* Applications (or enterprise plugins) must implement a new `RoleService` extending from
  `BaseRoleService` to provide a map of users to their app-specific roles. Roles continue to be
  modelled as simple strings for use both on server and client.
* The `HoistUser` superclass no longer holds / caches its roles directly, but instead calls into the
  new required `RoleService` dynamically when asked.
* Boilerplate around auth whitelists and resources has been better consolidated into the plugin,
  helping to clean up some repeated application-level `AuthenticationService` code.
* Hoist implementation endpoints have moved from `/hoistImpl/ -> /xh/` for brevity / clarity.
  Client-side plugins will be updated to use this new path. The implementation APIs used to
  login/logout and confirm auth / roles have changed, but again are handled by Hoist client plugin
  updates and do not require application-level changes.

[Commit Log](https://github.com/xh/hoist-core/compare/release-4.3.0...release-5.0.3)

## v4.3.0

### 🎁 New Features

* Server-side Excel export supports `LONG_TEXT` format with wrapped multi-line cells.

### 🐞 Bug Fixes

* Objects extending `JSONFormat` with `cacheJSON` enabled can be rendered at the top-level of a
  return.

[Commit Log](https://github.com/xh/hoist-core/compare/release-4.2.1...release-4.3.0)

## v4.2.1

* Added support for `activeOnly` argument to `UserAdminController` - required for xh/hoist-react#567

[Commit Log](https://github.com/xh/hoist-core/compare/release-4.2.0...release-4.2.1)

## v4.2.0

### 🎁 New Features

* Added support for PUT, PATCH, DELETE to BaseProxyService.

[Commit Log](https://github.com/xh/hoist-core/compare/release-4.1.0...release-4.2.0)

## v4.1.0

### 📚 Libraries

* Grails `3.3.5 -> 3.3.8`

[Commit Log](https://github.com/xh/hoist-core/compare/release-4.0.0...release-4.1.0)

## v4.0.0

### 💥 Breaking Changes

* Relevant `HoistImplController` endpoints now require a `clientUsername` param to ensure the client
  and server are in sync re. the currently active user. This resolves edge-case bugs around
  impersonation and preference, dashboard changes flushing changes on unload to the wrong
  server-side user. (#46)

[Commit Log](https://github.com/xh/hoist-core/compare/release-3.1.2...release-4.0.0)

## v3.1.2

### 🐞 Bug Fixes

* The `xhEmailDefaultDomain` config no longer needs a leading (and confusing) `@` - EmailService
  will prepend this automatically.
    * ⚠️ Note app-level configs with a leading `@` in place will need to be adjusted. (#43)

[Commit Log](https://github.com/xh/hoist-core/compare/release-3.1.1...release-3.1.2)

## v3.1.1

### 🐞 Bug Fixes

+ IdentityService.getUser() should not throw when called outside context of a request -- just return
  null. Important when e.g. looking for a username within service calls that might be triggered by a
  controller-based web request or a timer-based thread. 4130a9add8dd8ba22376ea69cfa3a3d095bdf6b0

[Commit Log](https://github.com/xh/hoist-core/compare/release-3.1.0...release-3.1.1)

## v3.1.0

### 🎁 New Features

* Group field added to Preferences for better organization and consistency with AppConfigs.<br><br>
  ⚠️ **Note** schema update required:

```sql
--MySQL
ALTER TABLE xh_preference
    ADD group_name VARCHAR(255);
UPDATE xh_preference
SET group_name = 'Default'
WHERE group_name IS NULL;
ALTER TABLE xh_preference MODIFY group_name VARCHAR (255) NOT NULL;
```

```sql
--SQL Server
ALTER TABLE xh_preference
    ADD group_name VARCHAR(255);
UPDATE xh_preference
SET group_name = 'Default'
WHERE group_name IS NULL;
ALTER TABLE xh_preference ALTER COLUMN group_name varchar(255) NOT NULL
```

* ClientError tracking gets a `userAlerted` flag to record whether or not the user was shown a
  pop-up dialog (vs. an error being reported quietly in the background).<br><br> ⚠️ **Note** schema
  update required:

```sql
-- SQL Server
ALTER TABLE xh_client_error
    ADD user_alerted bit NOT NULL DEFAULT 0
-- MySQL
ALTER TABLE xh_client_error
    ADD user_alerted bit(1) NOT NULL DEFAULT 0
```

### 🐞 Bug Fixes

* Log archiving fixed for apps with a dash or underscore in their appCode.

## v.3.0.4

### 🐞 Bugfixes

* Removed plugin grails-x-frame-options-plugin. It will be put into the hoist-sencha project. It is
  only needed in hoist-sencha apps. Hoist-react apps will get this header set by nginx.

## v.3.0.3

### 🎁 New Features

* Added plugin
  [grails-x-frame-options-plugin](https://github.com/mrhaki/grails-x-frame-options-plugin)
* This prevents XSS attacks by setting by default the most strict header setting `X-Frame-Options:
  DENY` on all responses from the grails server. You can relax this strict setting to `SAMEORIGIN`
  (and will probably want to) by adding `plugin.xframeoptions.sameOrigin = true` inside the grails
  clause of your application.groovy file (see piq-react for example).

## v3.0.2

### 📚 Libraries

* Gradle wrapper `4.8`

## v3.0.1

### 🎁 New Features

* Updates of following libraries:

```
grailsVersion=3.3.1 -> 3.3.5
grailsAsyncVersion=3.3.1 -> 3.3.2
gormVersion=6.1.7.RELEASE -> 6.1.9.RELEASE
```

* Note Grails update fixes support for the pathJar which helps fix long class path issues on
  Windows.
* Default theme is now the 'light' theme.

## v3.0.0

### 💥 Breaking Changes

* This release unwinds the multi environment config concept. See #30 for the corresponding issue.
* To take this update, developers need to also migrate to v5.X.X of hoist-react or v2.X.X of
  hoist-sencha and follow these steps in each environment:

#### Step 1

If you are doing this migration in a lower environment (dev, stage, uat) you may want to keep that
environment's configs. For example, if you are migrating the dev env app to this new code, and there
are configs in the dev_value column that you would like to keep in the dev environment, you first
need to manually copy these values from the dev field to the prod field in the dev admin config UI.

#### Step 2

Turn off your grails server and your webpack server (if applicable). Add a new 'value' column with
allow null, allow null in the old 'prod_value' column, then copy prod_value values over to the new
value column:

##### For MySQL DB:

```
ALTER TABLE `xh_config` ADD `value` LONGTEXT;
ALTER TABLE `xh_config` MODIFY `prod_value` LONGTEXT;
```

##### For MS SQL Server DB:

```
ALTER TABLE xh_config
ADD value varchar(max) NULL
ALTER COLUMN prod_value varchar(max)
```

##### For MySQL DB:

```
UPDATE `xh_config` SET `value` = `prod_value`
```

##### For MS SQL Server DB:

```
UPDATE xh_config SET value = prod_value
```

#### Step 3

Update app code in environment to use hoist-core v3.0.0 and hoist-react v5.X.X or hoist-sencha
v2.0.0.

Remove

```
supportedEnvironments = ['Staging', 'Development']
```

from grails-app/conf/application.groovy in your app. (Note that this line might appear twice - once
near the top if an app has customized and once in the "hoistDefaults" section.)

#### Step 4

Set value to not accept NULL and drop old columns:

##### For MySQL DB:

```
ALTER TABLE `xh_config`
  MODIFY `value` LONGTEXT NOT NULL;

ALTER TABLE `xh_config`
  DROP COLUMN `beta_value`, `stage_value`, `dev_value`, `prod_value`;
```

##### For MS SQL Server DB:

```
ALTER TABLE xh_config
  ALTER COLUMN value varchar(max) NOT NULL

ALTER TABLE xh_config
  DROP COLUMN prod_value, dev_value, stage_value, beta_value

```

### 🎁 New Features

* None

### 🐞 Bugfixes

* None

## v2.0.0

### 💥 Breaking Changes

* This release includes updates around how the key `appCode` and `appName` identifiers are read from
  application builds and what they represent. See #33 for the corresponding issue. This standardizes
  the meaning of these two identifiers on the client and server, and decouples the server-side
  appCode from the Gradle project name.
* To take this update, applications must ensure their `build.gradle` file populates these variables
  within a grails.build.info file created by Grails itself during the build. See e.g.
  [this commit to the Toolbox app](https://github.com/xh/toolbox/commit/073376eefba37ea0ffec073478bb628be447c77b)
  for an example of this change.
* Apps should audit their use of `Utils.appName` on the server-side and update to `Utils.appCode` if
  they need to continue accessing the shortname variant.

### 🎁 New Features

* None

### 🐞 Bugfixes

* None

## v1.2.0

### 💥 Breaking Changes

* None

### 🎁 New Features

This release adds support for
[InstanceConfigUtils](https://github.com/xh/hoist-core/blob/1fa43564fb77e11a04dd075d969b300f38252579/src/main/groovy/io/xh/hoist/util/InstanceConfigUtils.groovy)
, a utility for loading configuration properties from an external YAML file once on startup and
exposing them to the application as a map.

* These are intended to be minimal, low-level configs that apply to a particular deployed instance
  of the application and therefore are better sourced from a local file/volume vs. source code,
  JavaOpts, or database-driven ConfigService entries.
* Examples include the AppEnvironment as well as common Bootstrap requirements such as database
  credentials.
* See the class-level doc comment for additional details. Use of InstanceUtils is _not_ required to
  take this release.

### 🐞 Bugfixes

* Fix NPE breaking FeedbackService emailing. 8f07caf677dc0ed3a5ae6c8dd99dc59e2ffd8508
* Make LogLevel adjustments synchronous, so they reflect immediately in Admin console UI.
  dc387e885bea14b0443d5e984ccd74238fa6e7b7

------------------------------------------

Copyright © 2023 Extremely Heavy Industries Inc. - all rights reserved

------------------------------------------

📫☎️🌎 info@xh.io | https://xh.io
