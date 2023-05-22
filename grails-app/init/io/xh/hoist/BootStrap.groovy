/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist

import grails.util.Holders
import io.xh.hoist.util.DateTimeUtils
import io.xh.hoist.util.Utils

import static java.lang.Runtime.runtime

class BootStrap {

    def logLevelService

    def init = {servletContext ->
        logStartupMsg()
        ensureRequiredConfigsCreated()
        ensureRequiredPrefsCreated()

        def services = Utils.xhServices.findAll {it.class.canonicalName.startsWith('io.xh.hoist')}
        BaseService.parallelInit([logLevelService])
        BaseService.parallelInit(services)
    }

    def destroy = {}


    //------------------------
    // Implementation
    //------------------------
    private void logStartupMsg() {
        def hoist = Holders.currentPluginManager().getGrailsPlugin('hoist-core')

        log.info("""
\n
 __  __     ______     __     ______     ______
/\\ \\_\\ \\   /\\  __ \\   /\\ \\   /\\  ___\\   /\\__  _\\
\\ \\  __ \\  \\ \\ \\/\\ \\  \\ \\ \\  \\ \\___  \\  \\/_/\\ \\/
 \\ \\_\\ \\_\\  \\ \\_____\\  \\ \\_\\  \\/\\_____\\    \\ \\_\\
  \\/_/\\/_/   \\/_____/   \\/_/   \\/_____/     \\/_/
\n
          Hoist v${hoist.version} - ${Utils.getAppEnvironment()}
          Extremely Heavy - http://xh.io
            + ${runtime.availableProcessors()} available processors
            + ${String.format('%,d', (runtime.maxMemory() / 1000000).toLong())}mb available memory
            + ${DateTimeUtils.serverZoneId} is the default timezone
\n
        """)
    }

    private void ensureRequiredConfigsCreated() {
        Utils.configService.ensureRequiredConfigsCreated([
            xhActivityTrackingConfig: [
                valueType: 'json',
                defaultValue: [
                    enabled: true,
                    maxDataLength: 2000,
                    maxRows: [default: 10000, limit: 25000, options: [1000, 5000, 10000, 25000]]
                ],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures built-in Activity Tracking via TrackService.'
            ],
            xhAlertBannerConfig: [
                valueType: 'json',
                defaultValue: [enabled: true, interval: 30],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures support for showing an app-wide alert banner.\n\nAdmins configure and activate alert banners from the Hoist Admin console. To generally enable this system, set "enabled" to true and "interval" to a positive value (in seconds) to control how often connected apps check for a new alert.'
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
                defaultValue: 'GMT',
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Official TimeZone for this application - e.g. the zone of the head office. Used to format/parse business related dates that need to be considered and displayed consistently at all locations. Set to a valid Java TimeZone ID.'
            ],
            xhAppVersionCheckEnabled: [
                valueType: 'bool',
                defaultValue: true,
                groupName: 'xh.io',
                note: 'True to show an update prompt banner when the server reports to the client that a new version is available. Can be set to false to temporarily avoid an upgrade prompt (e.g. while validating a deploy). Use xhAppVersionCheckSecs to completely disable this feature.'
            ],
            xhAppVersionCheckSecs: [
                valueType: 'int',
                defaultValue: 30,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Frequency with which the version of the app should be checked. Value of -1 disables version checking.'
            ],
            xhAutoRefreshIntervals: [
                valueType: 'json',
                defaultValue: [app: -1],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Map of clientAppCodes to intervals (in seconds) on which the client-side AutoRefreshService should fire. Note the xhAutoRefreshEnabled preference must also be true for the client service to activate.'
            ],
            xhClientErrorConfig: [
                valueType: 'json',
                defaultValue: [intervalMins: 2, maxErrors: 25],
                groupName: 'xh.io',
                note: 'Configures handling of client error reports. Errors are queued when received and processed every [intervalMins]. If more than [maxErrors] arrive within an interval, further reports are dropped to avoid storms of errors from multiple clients.'
            ],
            xhEmailDefaultDomain: [
                valueType: 'string',
                defaultValue: 'xh.io',
                groupName: 'xh.io',
                note: 'Default domain name appended by Hoist EmailServices when unqualified usernames are passed to the service as email recipients/senders.'
            ],
            xhEmailDefaultSender: [
                valueType: 'string',
                defaultValue: 'support@xh.io',
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
                note: 'Email address to which support and feedback submissions should be sent.'
            ],
            xhEnableImpersonation: [
                valueType: 'bool',
                defaultValue: false,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'True to allow Hoist Admins to impersonate other users.'
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
            xhIdleConfig: [
                valueType: 'json',
                defaultValue: [timeout: -1, appTimeouts: [:]],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Governs how client application will enter "sleep mode", suspending background requests and prompting the user to reload to resume.  Timeouts are in minutes of inactivity.'
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
                    heapDumpDir: null
                ],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Configures built-in Memory Monitoring.'
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
        Utils.prefService.ensureRequiredPrefsCreated([
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

}
