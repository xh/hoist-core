# Changelog

## 10.0-SNAPSHOT - unreleased
Version 7.0 includes a major upgrade of several underlying libraries, especially grails (v4.0.6) and spring-boot (2.1.13)
With this version, Hoist can now be run on Java versions 8 - 11.  We have also cleaned up and enhanced some core
APIs around Exception Handling and JSON parsing and configuration.

### 🎁 New Features
* Add support for Preference Diffing in the hoist-react admin tool.

### 🎁 Breaking Changes
* The trait `AsyncSupport` with its single method `asyncTask` has been removed.  Use the equivalent method `task` 
from `grails.async.Promises` instead.

* The method `subscribeWithSession` on `BaseService` has been removed.  Use `subscribe` instead.

### ⚙️ Technical
* This release upgrades the major version of grails from 3.3.9 to 4.0.3.  This major release
includes the following upgrades of related libraries:
    * spring boot `1.x -> 2.1.13` 
    * groovy `2.4.15 -> 2.5.6`
    * gradle `4.10.3 -> 5.6.4`
    * gorm `6.1.11 -> 7.0.4`
    * hibernate `5.1.10 -> 5.4.14`
    * org.grails.plugins:mail `2.0.0 -> 3.0.0`
    * apache poi  `3.1.7` -> `4.1.2`
    
* HttpClient has been upgraded from `4.5.6` -> `5.5.0`.  Package names have changed, and applications using
this API (e.g. with `JSONClient`) will need to update their imports statements to reflect the new locations @
`org.apache.hc.client5.http`  and `org.apache.hc.core5.http`.  See toolbox for examples.
  
* Please see the grails docs as well as the associated toolbox branch for more information
on required changes to config and dependency files.

* Applications will be required to add the `@Transactional` or `@ReadOnly` annotations to service and controller
methods that update data with GORM. 

[Commit Log](https://github.com/xh/hoist-core/compare/v9.1.0...develop)



## 9.1.0 - 2020-12-22

### 🎁 New Features
* Built-in logging utils -- `withDebug`, `withInfo`, `compactErrorLog` and `compactDebugLog`  will log
 username for logging done in the context of a user request. 
* New method `IdentityService.getUsername()` for efficient access to username when no additional details about
current user are needed.

### ⚙️ Technical
* Improve consistency of exception descriptions in logs.
* Remove repeated exception descriptions in logs -- `withDebug` and `withInfo` will no longer print exception 
details.
* TrackService will now log to a dedicated daily log

[Commit Log](https://github.com/xh/hoist-core/compare/v9.0.0...v9.1.0)

## 9.0.0 - 2020-12-17

### 💥 Breaking Changes

* `LogSupport` API enhancements:
  * `logErrorCompact()` and `logDebugCompact()` now only show stacktraces on `TRACE`
  *  `withInfo()` and  `withDebug()` now log only once _after_ execution has completed. Raising the log level of the 
     relevant class or package to `TRACE` will cause these utils to also log a line _before_ execution, as they did 
     before. (As always, log levels can be adjusted dynamically at runtime via the Admin Console.)
  * The upgrade to these two utils mean that they **completely replace** `withShortInfo()` and `withShortDebug()`, 
     which have both been **removed** as part of this change. 
  *  Additional stacktraces have been removed from default logging.
 
 ### ⚙️ Technical
* `RoutineException`s are now returned with HttpStatus `400` to client, rather than `500` 

[Commit Log](https://github.com/xh/hoist-core/compare/v8.7.3...v9.0.0)

## 8.7.3 - 2020-12-15

* Default exception logging in `ExceptionRender` will no longer include stacktraces,
but will instead use `LogSupport.logErrorCompact()`.  To see stacktraces for 
any given logger, set the logging level to `DEBUG`.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.7.2...v8.7.3)

## 8.7.2 - 2020-12-15

### 🐞 Bug Fixes

* Fixed bug preventing cleanup of MemoryMonitoringService snapshots.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.7.1...v8.7.2)

## 8.7.1 - 2020-12-11

### ⚙️ Technical

* Minor enhancements to `JSONBlobService` API.

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
  additional column on blob table, e.g. ```sql alter table xh_json_blob add meta varchar(max) go ```
* Introduce new `AppEnvironment.TEST` enumeration value.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.6.0...v8.6.1)


## 8.6.0 - 2020-10-25

* `JsonBlobService`: Enhancements to archiving, new columns and new unique key constraint.
  - Apps will need to modify the `xh_json_blob` table with new `meta` and `archived_date` columns
    and related unique constraint. SAMPLE migration SQL below:

    ```sql
    alter table xh_json_blob add archived_date bigint not null go
    alter table xh_json_blob drop column archived go
    alter table xh_json_blob add constraint idx_xh_json_blob_unique_key unique (archived_date, type, owner, name)
    ```

  - Apps should update to `hoist-react >= 36.6.0`.

[Commit Log](https://github.com/xh/hoist-core/compare/v8.5.0...v8.6.0)


## 8.5.0 - 2020-10-07

* `JsonBlobService`: Use more scalable token-based access; support archiving.

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
[contact us](https://xh.io/contact/).

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
  * A new `JSONParser` API in the `io.xh.hoist.json` package that provides JSON parsing of text and
    input streams. This API is designed to be symmetrical with the existing `JSONFormatter.`
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
    throw an exception on startup related to a failure instantiating `WebSocketService`. Apps should
    *not* need to make any changes to their own code / services aside from this new dep.
  * This service and its related endpoints integrate with client-side websocket support and admin
    tools added to Hoist React v26.
  * As per the included class-level documentation, applications must update their Application.groovy
    file to expose an endpoint for connections and wire up a `HoistWebSocketHandler` to relay
    connection events to the new service.

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

### 🎁  New Features

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
ALTER TABLE xh_preference ADD group_name VARCHAR(255);
UPDATE xh_preference SET group_name = 'Default' WHERE group_name IS NULL;
ALTER TABLE xh_preference MODIFY group_name VARCHAR(255) NOT NULL;
```

```sql
--SQL Server
ALTER TABLE xh_preference ADD group_name VARCHAR(255);
UPDATE xh_preference SET group_name = 'Default' WHERE group_name IS NULL;
ALTER TABLE xh_preference ALTER COLUMN group_name varchar(255) NOT NULL
```


* ClientError tracking gets a `userAlerted` flag to record whether or not the user was shown a
  pop-up dialog (vs. an error being reported quietly in the background).<br><br> ⚠️ **Note** schema
  update required:

```sql
-- SQL Server
ALTER TABLE xh_client_error ADD user_alerted bit NOT NULL DEFAULT 0
-- MySQL
ALTER TABLE xh_client_error ADD user_alerted bit(1) NOT NULL DEFAULT 0
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
[InstanceConfigUtils](https://github.com/xh/hoist-core/blob/1fa43564fb77e11a04dd075d969b300f38252579/src/main/groovy/io/xh/hoist/util/InstanceConfigUtils.groovy),
a utility for loading configuration properties from an external YAML file once on startup and
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

Copyright © 2020 Extremely Heavy Industries Inc. - all rights reserved

------------------------------------------

📫☎️🌎 info@xh.io | https://xh.io/contact
