/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist

import grails.util.Holders
import io.xh.hoist.admin.ConnPoolMonitoringConfig
import io.xh.hoist.admin.MemoryMonitoringConfig
import io.xh.hoist.alertbanner.AlertBannerConfig
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.config.AppConfig
import io.xh.hoist.config.ChangelogConfig
import io.xh.hoist.config.ConfigSpec
import io.xh.hoist.config.IdleConfig
import io.xh.hoist.entra.EntraIdConfig
import io.xh.hoist.environment.EnvPollConfig
import io.xh.hoist.export.ExportConfig
import io.xh.hoist.ldap.LdapConfig
import io.xh.hoist.log.LogArchiveConfig
import io.xh.hoist.log.LogSupport
import io.xh.hoist.monitor.MonitorConfig
import io.xh.hoist.pref.PreferenceSpec
import io.xh.hoist.telemetry.metric.MetricsConfig
import io.xh.hoist.telemetry.trace.TraceConfig
import io.xh.hoist.track.ActivityTrackingConfig
import io.xh.hoist.track.ClientErrorConfig
import io.xh.hoist.util.Utils
import io.xh.hoist.websocket.WebSocketConfig

import java.time.Instant
import java.time.ZoneId

import static io.xh.hoist.util.DateTimeUtils.serverZoneId
import static io.xh.hoist.BaseService.parallelInit
import static java.lang.Runtime.runtime

class BootStrap implements LogSupport {

    def logLevelService,
        configService,
        clusterService,
        metricsService,
        traceService,
        prefService

    def init = {servletContext ->
        def initStart = Instant.now()
        logStartupMsg()

        // Ordered, early initialization of core services
        configService.initialize()
        ensureRequiredConfigsCreated()
        ensureExpectedServerTimeZone()

        traceService.initialize()
        traceService.startServerLoadSpan()
        traceService.withSpan(name: 'xh.server.hoistInit', startTime: initStart, caller: this) {
            logLevelService.initialize()
            clusterService.initialize()
            metricsService.initialize()

            // All other services in parallel...
            def services = Utils.xhServices.findAll { it.class.canonicalName.startsWith('io.xh.hoist') }
            parallelInit(services)

            ensureRequiredPrefsCreated()
        }
    }

    def destroy = {}


    //------------------------
    // Implementation
    //------------------------
    private void logStartupMsg() {
        def hoist = Holders.currentPluginManager().getGrailsPlugin('hoist-core')
        logInfo("""
\n
 __  __     ______     __     ______     ______
/\\ \\_\\ \\   /\\  __ \\   /\\ \\   /\\  ___\\   /\\__  _\\
\\ \\  __ \\  \\ \\ \\/\\ \\  \\ \\ \\  \\ \\___  \\  \\/_/\\ \\/
 \\ \\_\\ \\_\\  \\ \\_____\\  \\ \\_\\  \\/\\_____\\    \\ \\_\\
  \\/_/\\/_/   \\/_____/   \\/_/   \\/_____/     \\/_/
\n
          Hoist v${hoist.version} - ${Utils.appEnvironment}
          Extremely Heavy - https://xh.io
            + Cluster ${ClusterService.clusterName}
            + Instance ${ClusterService.instanceName}
            + JDK ${System.getProperty('java.version')} (${System.getProperty('java.vendor')})
            + ${runtime.availableProcessors()} available processors
            + ${String.format('%,d', (runtime.maxMemory() / 1000000).toLong())}mb available memory
            + JVM TimeZone is ${serverZoneId}
\n
        """)
    }

    private void ensureRequiredConfigsCreated() {
        configService.ensureRequiredConfigsCreated([
            new ConfigSpec(
                name: 'xhActivityTrackingConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: ActivityTrackingConfig,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures built-in activity tracking via `TrackService`, including which users and categories are logged at each severity, limits on entry volume and data size, and client health reporting.'
            ),
            new ConfigSpec(
                name: 'xhAlertBannerConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: AlertBannerConfig,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures the app-wide alert banner. Set `enabled` to `true` to make the feature available, then compose and activate banners from the Admin Console. Clients pick up changes on the `xhEnvPollConfig.interval`.'
            ),
            new ConfigSpec(
                name: 'xhAppInstances',
                valueType: 'json',
                defaultValue: [],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'List of root URLs for this app\'s deployments in other environments. Offered as remote targets by the Admin Console\'s "Compare w/ Remote" tools.'
            ),
            new ConfigSpec(
                name: 'xhAppTimeZone',
                valueType: 'string',
                defaultValue: 'UTC',
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Official time zone of the application - typically that of the head office - as a Java TimeZone ID. Used to parse and format business dates that must read the same at every location.'
            ),
            new ConfigSpec(
                name: 'xhAutoRefreshIntervals',
                valueType: 'json',
                defaultValue: [app: -1],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Map of client app code to the interval, in seconds, on which that app\'s `AutoRefreshService` fires. Use `-1` to disable. Users must also have the `xhAutoRefreshEnabled` preference set to `true`.'
            ),
            new ConfigSpec(
                name: 'xhChangelogConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: ChangelogConfig,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures the built-in changelog (release notes). Disable the feature entirely, exclude particular versions or categories of change, or limit visibility to users with selected roles.'
            ),
            new ConfigSpec(
                name: 'xhClientErrorConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: ClientErrorConfig,
                groupName: 'xh.io',
                note: 'Configures handling of client error reports. Reports are queued as received and processed every `intervalMins`.'
            ),
            new ConfigSpec(
                name: 'xhConnPoolMonitoringConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: ConnPoolMonitoringConfig,
                groupName: 'xh.io',
                note: 'Configures built-in monitoring of the JDBC connection pool, including how often to snapshot pool stats and how many snapshots to retain.'
            ),
            new ConfigSpec(
                name: 'xhEmailDefaultDomain',
                valueType: 'string',
                defaultValue: 'example.com',
                groupName: 'xh.io',
                note: 'Domain appended by `EmailService` when an unqualified username is given as a sender or recipient.'
            ),
            new ConfigSpec(
                name: 'xhEmailDefaultSender',
                valueType: 'string',
                defaultValue: 'support@example.com',
                groupName: 'xh.io',
                note: 'Sender address used by `EmailService` when none is specified.'
            ),
            new ConfigSpec(
                name: 'xhEmailFilter',
                valueType: 'string',
                defaultValue: AppConfig.NONE,
                groupName: 'xh.io',
                note: 'Comma-separated list of the only addresses `EmailService` may send to, for dev and test environments. Mail to any other address is quietly dropped. Set to `none` to send to all recipients.'
            ),
            new ConfigSpec(
                name: 'xhEmailOverride',
                valueType: 'string',
                defaultValue: AppConfig.NONE,
                groupName: 'xh.io',
                note: 'Single address to which `EmailService` redirects all mail, regardless of the intended recipients. Use in dev and test environments to exercise real sending without reaching end users. Set to `none` to disable.'
            ),
            new ConfigSpec(
                name: 'xhEmailSupport',
                valueType: 'string',
                defaultValue: AppConfig.NONE,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Address that receives user feedback and client error reports, or `none` to disable those emails.'
            ),
            new ConfigSpec(
                name: 'xhEnableImpersonation',
                valueType: 'bool',
                defaultValue: false,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Enables identity impersonation by authorized users.'
            ),
            new ConfigSpec(
                name: 'xhEnableLogViewer',
                valueType: 'bool',
                defaultValue: true,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Enables the Admin Console log viewer and its server-side endpoints.'
            ),
            new ConfigSpec(
                name: 'xhEnableMonitoring',
                valueType: 'bool',
                defaultValue: true,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Enables the Admin Console Monitors tab and the server-side jobs that run status monitors.'
            ),
            new ConfigSpec(
                name: 'xhEntraIdConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: EntraIdConfig,
                groupName: 'xh.io',
                note: 'Configures `EntraIdService` for querying users and groups in Microsoft Entra ID via Microsoft Graph. Requires `xhEntraTenantId`, `xhEntraClientId`, and `xhEntraClientSecret`.'
            ),
            new ConfigSpec(
                name: 'xhEntraTenantId',
                valueType: 'string',
                defaultValue: AppConfig.NONE,
                groupName: 'xh.io',
                note: 'Tenant ID (GUID) of the app\'s Microsoft Entra ID tenant, used by `EntraIdService` and any other code that works with the tenant. Relay to pre-auth clients for OAuth login via `AuthenticationService.getClientConfig()` where needed.'
            ),
            new ConfigSpec(
                name: 'xhEntraClientId',
                valueType: 'string',
                defaultValue: AppConfig.NONE,
                groupName: 'xh.io',
                note: 'Client ID (GUID) of the app\'s Entra ID app registration, used by `EntraIdService` and any other code that works with the registration. Relay to pre-auth clients for OAuth login via `AuthenticationService.getClientConfig()` where needed.'
            ),
            new ConfigSpec(
                name: 'xhEntraClientSecret',
                valueType: 'pwd',
                defaultValue: AppConfig.NONE,
                groupName: 'xh.io',
                note: 'Client secret for the Entra ID app registration used by `EntraIdService`.'
            ),
            new ConfigSpec(
                name: 'xhEnvPollConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: EnvPollConfig,
                groupName: 'xh.io',
                note: 'Configures how clients poll the server for version, instance, and auth changes.\n\n' +
                    '- `interval`: Seconds between polls, or `-1` to disable.\n' +
                    '- `onVersionChange`: Action taken when a new app version is available, one of:\n' +
                    '    - `forceReload`: Reload immediately. Use when a new server is incompatible with the deployed client.\n' +
                    '    - `promptReload`: Show a banner prompting users to reload when convenient.\n' +
                    '    - `silent`: Take no action.'
            ),
            new ConfigSpec(
                name: 'xhExpectedServerTimeZone',
                valueType: 'string',
                defaultValue: '*',
                groupName: 'xh.io',
                note: 'Time zone the server JVM is expected to run in, as a Java TimeZone ID. Checked once at startup: the server fails to start if the value is invalid or does not match the JVM\'s zone. Changing it does not affect a running server or the JVM\'s default zone. Set to `*` to skip the check.'
            ),
            new ConfigSpec(
                name: 'xhExportConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: ExportConfig,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures Excel export, including the cell-count thresholds above which the server streams the workbook and the client warns that the export may take a while.'
            ),
            new ConfigSpec(
                name: 'xhFlags',
                valueType: 'json',
                defaultValue: [:],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Map of flags for experimental features.'
            ),
            new ConfigSpec(
                name: 'xhIdleConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: IdleConfig,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures when an idle client enters sleep mode, suspending background requests until the user reloads. `timeout` is minutes of inactivity, overridable per client app in `appTimeouts`. Use `-1` to disable.'
            ),
            new ConfigSpec(
                name: 'xhLdapConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: LdapConfig,
                groupName: 'xh.io',
                note: 'Configures `LdapService` for querying users and groups from one or more LDAP servers. Requires `xhLdapUsername` and `xhLdapPassword` when enabled.'
            ),
            new ConfigSpec(
                name: 'xhLdapUsername',
                valueType: 'string',
                defaultValue: AppConfig.NONE,
                groupName: 'xh.io',
                note: 'Distinguished name of the account `LdapService` binds as when querying, or `none` if LDAP is not in use.'
            ),
            new ConfigSpec(
                name: 'xhLdapPassword',
                valueType: 'pwd',
                defaultValue: AppConfig.NONE,
                groupName: 'xh.io',
                note: 'Password for the `xhLdapUsername` account, or `none` if LDAP is not in use.'
            ),
            new ConfigSpec(
                name: 'xhLogArchiveConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: LogArchiveConfig,
                groupName: 'xh.io',
                note: 'Configures automatic archiving of log files. Files older than `archiveAfterDays` are moved into zipped bundles within `archiveFolder`.'
            ),
            new ConfigSpec(
                name: 'xhMemoryMonitoringConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: MemoryMonitoringConfig,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures built-in monitoring of JVM memory and garbage collection, including snapshot frequency, retention, and where to write heap dumps requested from the Admin Console.'
            ),
            new ConfigSpec(
                name: 'xhMonitorConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: MonitorConfig,
                groupName: 'xh.io',
                note: 'Configures server-side status monitors and their notifications. `warnNotifyThreshold` and `failNotifyThreshold` are the number of consecutive refresh cycles a monitor must spend in that status before alerting.'
            ),
            new ConfigSpec(
                name: 'xhMonitorEmailRecipients',
                valueType: 'string',
                defaultValue: AppConfig.NONE,
                groupName: 'xh.io',
                note: 'Comma-separated addresses that receive status monitor alerts, or `none` to disable emailed alerts.'
            ),
            new ConfigSpec(
                name: 'xhMetricsConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: MetricsConfig,
                groupName: 'xh.io',
                note: 'Configures export of observable metrics to Prometheus and OTLP endpoints. Select the metrics to export with `xhMetricsPublished`.'
            ),
            new ConfigSpec(
                name: 'xhTraceConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: TraceConfig,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures distributed tracing, including sampling rate and rules, OTLP export, and JDBC tracing.'
            ),
            new ConfigSpec(
                name: 'xhMetricsPublished',
                valueType: 'json',
                defaultValue: [],
                groupName: 'xh.io',
                note: 'List of metric names to include in Prometheus, OTLP, and other exports. An empty list exports nothing. Manage via the Publish and Unpublish actions on the Admin Console\'s Metrics tab rather than editing this list directly.'
            ),
            new ConfigSpec(
                name: 'xhWebSocketConfig',
                valueType: 'json',
                defaultValue: [:],
                typedClass: WebSocketConfig,
                groupName: 'xh.io',
                note: 'Configures the WebSocket sessions Hoist manages for connected clients, including send time and buffer size limits.'
            )
        ])
    }

    private void ensureRequiredPrefsCreated() {
        prefService.ensureRequiredPrefsCreated([
            new PreferenceSpec(
                name: 'xhAutoRefreshEnabled',
                type: 'bool',
                defaultValue: true,
                groupName: 'xh.io',
                notes: 'True to enable the client AutoRefreshService, which will trigger a refresh of client app data if/as specified by the xhAutoRefreshIntervals config. Note if disabled at the app level via config, this pref will have no effect.'
            ),
            new PreferenceSpec(
                name: 'xhIdleDetectionDisabled',
                type: 'bool',
                defaultValue: false,
                groupName: 'xh.io',
                notes: 'Set to true prevent IdleService from suspending the application due to inactivity.'
            ),
            new PreferenceSpec(
                name: 'xhLastReadChangelog',
                type: 'string',
                defaultValue: '0.0.0',
                groupName: 'xh.io',
                notes: 'The most recent changelog entry version viewed by the user - read/written by XH.changelogService.'
            ),
            new PreferenceSpec(
                name: 'xhShowVersionBar',
                type: 'string',
                defaultValue: 'auto',
                groupName: 'xh.io',
                notes: "Control display of Hoist footer with app version info. Options are 'auto' (show in non-prod env, or always for admins), 'always', and 'never'."
            ),
            new PreferenceSpec(
                name: 'xhSizingMode',
                type: 'json',
                defaultValue: [:],
                groupName: 'xh.io',
                notes: 'Sizing mode used by Grid and any other responsive components. Keyed by platform: [desktop|mobile|tablet].'
            ),
            new PreferenceSpec(
                name: 'xhTheme',
                type: 'string',
                defaultValue: 'system',
                groupName: 'xh.io',
                notes: 'Visual theme for the client application - "light", "dark", or "system".'
            )
        ])
    }

    /**
     * Validates that the JVM TimeZone matches the value specified by the `xhExpectedServerTimeZone`
     * application config. This is intended to ensure that the JVM is running in the expected Zone,
     * typically set to the same Zone as the app's primary database.
     */
    private void ensureExpectedServerTimeZone() {
        def confZone = configService.getString('xhExpectedServerTimeZone')
        if (confZone == '*') {
            logWarn(
                "WARNING - a timezone has not yet been specified for this application's server.  " +
                "This can lead to bugs and data corruption in development and production.  " +
                "Please specify your expected timezone in the `xhExpectedServerTimeZone` config."
            )
            return
        }

        ZoneId confZoneId
        try {
            confZoneId = ZoneId.of(confZone)
        } catch (ignored) {
            throw new IllegalStateException("Invalid xhExpectedServerTimeZone config: '$confZone' not a valid ZoneId.")
        }

        if (confZoneId != serverZoneId) {
            throw new IllegalStateException("JVM TimeZone of '${serverZoneId}' does not match value of '${confZoneId}' required by xhExpectedServerTimeZone config. Set JVM arg '-Duser.timezone=${confZoneId}' to change the JVM Zone, or update the config value in the database.")
        }
    }

}
