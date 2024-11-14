/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist

import grails.util.Holders
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.Utils

import java.time.ZoneId

import static io.xh.hoist.util.DateTimeUtils.serverZoneId
import static io.xh.hoist.BaseService.parallelInit
import static java.lang.Runtime.runtime

class BootStrap implements LogSupport {

    def logLevelService,
        configService,
        clusterService,
        prefService

    def init = {servletContext ->
        logStartupMsg()
        ensureRequiredConfigsCreated()
        ensureRequiredPrefsCreated()

        ensureExpectedServerTimeZone()

        def services = Utils.xhServices.findAll {it.class.canonicalName.startsWith('io.xh.hoist')}
        parallelInit([logLevelService])
        parallelInit([clusterService])
        parallelInit(services)
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
            + ${runtime.availableProcessors()} available processors
            + ${String.format('%,d', (runtime.maxMemory() / 1000000).toLong())}mb available memory
            + JVM TimeZone is ${serverZoneId}
\n
        """)
    }

    private void ensureRequiredConfigsCreated() {
        configService.ensureRequiredConfigsCreated([
            xhActivityTrackingConfig: [
                valueType: 'json',
                defaultValue: [
                    enabled: true,
                    logData: false,
                    maxDataLength: 2000,
                    maxRows: [default: 10000, limit: 25000, options: [1000, 5000, 10000, 25000]]
                ],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures built-in Activity Tracking via TrackService.'
            ],
            xhAlertBannerConfig: [
                valueType: 'json',
                defaultValue: [enabled: true],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures support for showing an app-wide alert banner.\n\nAdmins configure and activate alert banners from the Hoist Admin console. To generally enable this system, set "enabled" to true. The xhEnvPollConfig.interval config governs client polling for updates.'
            ],
            xhAppInstances: [
                valueType: 'json',
                defaultValue: [],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'List of root URLs for running instances of this app across environments. Currently only used for as a convenience feature in the Admin config diff tool.'
            ],
            xhAppTimeZone: [
                valueType: 'string',
                defaultValue: 'UTC',
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Official TimeZone for this application - e.g. the zone of the head office. Used to format/parse business related dates that need to be considered and displayed consistently at all locations. Set to a valid Java TimeZone ID.'
            ],
            xhAutoRefreshIntervals: [
                valueType: 'json',
                defaultValue: [app: -1],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Map of clientAppCodes to intervals (in seconds) on which the client-side AutoRefreshService should fire. Note the xhAutoRefreshEnabled preference must also be true for the client service to activate.'
            ],
            xhChangelogConfig: [
                valueType: 'json',
                defaultValue: [enabled: true, excludedVersions: [], excludedCategories: [], limitToRoles: []],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures built-in application changelog (release notes), with options to disable the feature entirely, exclude particular releases or categories of changes from the log, and/or only show to users with selected roles.'
            ],
            xhClientErrorConfig: [
                valueType: 'json',
                defaultValue: [intervalMins: 2, maxErrors: 25],
                groupName: 'xh.io',
                note: 'Configures handling of client error reports. Errors are queued when received and processed every [intervalMins]. If more than [maxErrors] arrive within an interval, further reports are dropped to avoid storms of errors from multiple clients.'
            ],
            xhConnPoolMonitoringConfig: [
                valueType: 'json',
                defaultValue: [
                    enabled: true,
                    snapshotInterval: 60,
                    maxSnapshots: 1440,
                    writeToLog: false
                ],
                groupName: 'xh.io',
                note: 'Configures built-in JDBC connection pool monitoring.'
            ],
            xhEmailDefaultDomain: [
                valueType: 'string',
                defaultValue: 'example.com',
                groupName: 'xh.io',
                note: 'Default domain name appended by Hoist EmailServices when unqualified usernames are passed to the service as email recipients/senders.'
            ],
            xhEmailDefaultSender: [
                valueType: 'string',
                defaultValue: 'support@example.com',
                groupName: 'xh.io',
                note: 'Email address for Hoist EmailService to use as default sender address.'
            ],
            xhEmailFilter: [
                valueType: 'string',
                defaultValue: 'none',
                groupName: 'xh.io',
                note: 'Comma-separated list of email addresses to which Hoist EmailService can send mail. For testing / dev purposes. If specified, emails to addresses not in this list will be quietly dropped. Value "none" does not filter recipients.'
            ],
            xhEmailOverride: [
                valueType: 'string',
                defaultValue: 'none',
                groupName: 'xh.io',
                note: 'Email address to which Hoist emailService should send all mail, regardless of specified recipient. For testing / dev purposes. Use to test actual sending of mails while still not mailing end-users. Value "none" disables any override.'
            ],
            xhEmailSupport: [
                valueType: 'string',
                defaultValue: 'none',
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Email address to which support and feedback submissions should be sent. Value "none" to disable support emails.'
            ],
            xhEnableImpersonation: [
                valueType: 'bool',
                defaultValue: false,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'True to enable identity impersonation by authorized users.'
            ],
            xhEnableLogViewer: [
                valueType: 'bool',
                defaultValue: true,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'True to enable the log viewer included with the Hoist Admin console as well as the associated server-side endpoints.'
            ],
            xhEnableMonitoring: [
                valueType: 'bool',
                defaultValue: true,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'True to enable the monitor tab included with the Hoist Admin console and the associated server-side jobs'
            ],
            xhEnvPollConfig: [
                valueType: 'json',
                defaultValue: [
                    interval: 10,
                    onVersionChange: configService.getMap('xhAppVersionCheck', [mode: 'promptReload']).get('mode')
                ],
                groupName: 'xh.io',
                note: "Controls client calls to server to poll for version, instance changes, or auth changes. Supports the following options:\n\n" +
                    "- interval: Frequency (in seconds) with which the status of the app server should be polled. Value of -1 disables checking.\n" +
                    "- onVersionChange: Action taken by client upon a new version becoming available, one of:\n" +
                    "\t+ 'forceReload': Force clients to refresh immediately. To be used when an updated server is known to be incompatible with a previously deployed client.\n" +
                    "\t+ 'promptReload': Show an update prompt banner, allowing users to refresh when convenient.\n" +
                    "\t+ 'silent': No action taken."
            ],
            xhExpectedServerTimeZone: [
                valueType: 'string',
                defaultValue: '*',
                groupName: 'xh.io',
                note: 'Expected time zone of the server-side JVM - set to a valid Java TimeZone ID. NOTE: this config is checked at startup to ensure the server is running in the expected zone and will throw a fatal exception if it is invalid or does not match the zone reported by Java.\n\nChanging this config has no effect on a running server, and will not itself change the default Zone of the JVM.\n\nIf you REALLY do not want this behavior, a value of "*" will suppress this check.'
            ],
            xhExportConfig: [
                valueType: 'json',
                defaultValue: [
                    streamingCellThreshold: 100000,
                    toastCellThreshold: 3000
                ],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures exporting data to Excel.'
            ],
            xhFlags: [
                valueType: 'json',
                defaultValue: [:],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Flags for experimental features.'
            ],
            xhIdleConfig: [
                valueType: 'json',
                defaultValue: [timeout: 120, appTimeouts: [:]],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Governs how client application will enter "sleep mode", suspending background requests and prompting the user to reload to resume.  Timeouts are in minutes of inactivity. -1 to disable.'
            ],
            xhLdapConfig: [
                valueType: 'json',
                defaultValue: [
                    enabled: false,
                    timeoutMs: 60000,
                    cacheExpireSecs: 300,
                    servers: [
                        [
                            host: '',
                            baseUserDn: '',
                            baseGroupDn: '',
                        ]
                    ]
                ],
                groupName: 'xh.io',
                note: 'Supports connecting to LDAP servers.'
            ],
            xhLdapUsername: [
                valueType: 'string',
                defaultValue: 'none',
                groupName: 'xh.io'
            ],
            xhLdapPassword: [
                valueType: 'pwd',
                defaultValue: 'none',
                groupName: 'xh.io'
            ],
            xhLogArchiveConfig: [
                valueType: 'json',
                defaultValue: [
                    archiveAfterDays: 30,
                    archiveFolder: 'archive'
                ],
                groupName: 'xh.io',
                note: 'Configures automatic cleanup and archiving of log files. Files older than "archiveAfterDays" will be moved into zipped bundles within the specified "archiveFolder".'
            ],
            xhMemoryMonitoringConfig: [
                valueType: 'json',
                defaultValue: [
                    enabled: true,
                    snapshotInterval: 60,
                    maxSnapshots: 1440,
                    heapDumpDir: null,
                    preservePastInstances: true,
                    maxPastInstances: 10
                ],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures built-in memory usage and GC monitoring.'
            ],
            xhMonitorConfig: [
                valueType: 'json',
                defaultValue: [
                    monitorRefreshMins: 10,
                    failNotifyThreshold: 2,
                    warnNotifyThreshold: 5,
                    monitorStartupDelayMins: 1,
                    monitorRepeatNotifyMins: 60,
                    monitorTimeoutSecs: 15,
                    writeToMonitorLog: true
                ],
                groupName: 'xh.io',
                note: 'Configures server-side status monitoring and notifications. Note failNotifyThreshold and warnNotifyThreshold are the number of refresh cycles a monitor will need to be in said status to trigger "alertMode".'
            ],
            xhMonitorEmailRecipients: [
                valueType: 'string',
                defaultValue: 'none',
                groupName: 'xh.io',
                note: 'Email address to which status monitor alerts should be sent. Value "none" disables emailed alerts.'
            ],
            xhWebSocketConfig: [
                valueType: 'json',
                defaultValue: [
                    sendTimeLimitMs: 1000,
                    bufferSizeLimitBytes: 1000000
                ],
                groupName: 'xh.io',
                note: 'Parameters for the managed WebSocket sessions created by Hoist.'
            ]
        ])
    }

    private void ensureRequiredPrefsCreated() {
        prefService.ensureRequiredPrefsCreated([
            xhAutoRefreshEnabled: [
                type: 'bool',
                defaultValue: true,
                groupName: 'xh.io',
                note: 'True to enable the client AutoRefreshService, which will trigger a refresh of client app data if/as specified by the xhAutoRefreshIntervals config. Note if disabled at the app level via config, this pref will have no effect.'
            ],
            xhIdleDetectionDisabled: [
                type: 'bool',
                defaultValue: false,
                groupName: 'xh.io',
                note: 'Set to true prevent IdleService from suspending the application due to inactivity.'
            ],
            xhLastReadChangelog: [
                type: 'string',
                defaultValue: '0.0.0',
                groupName: 'xh.io',
                note: 'The most recent changelog entry version viewed by the user - read/written by XH.changelogService.'
            ],
            xhShowVersionBar: [
                type: 'string',
                defaultValue: 'auto',
                groupName: 'xh.io',
                note: "Control display of Hoist footer with app version info. Options are 'auto' (show in non-prod env, or always for admins), 'always', and 'never'."
            ],
            xhSizingMode: [
                type: 'json',
                defaultValue: [:],
                groupName: 'xh.io',
                note: 'Sizing mode used by Grid and any other responsive components. Keyed by platform: [desktop|mobile|tablet].'
            ],
            xhTheme: [
                type: 'string',
                defaultValue: 'system',
                groupName: 'xh.io',
                note: 'Visual theme for the client application - "light", "dark", or "system".'
            ]
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
