/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */
package io.xh.hoist

import grails.util.Holders
import io.xh.hoist.util.Utils

import static java.lang.Runtime.runtime

class BootStrap {

    def init = {servletContext ->
        logStartupMsg()
        ensureRequiredConfigsCreated()
        ensureRequiredPrefsCreated()

        def services = Utils.xhServices.findAll {it.class.canonicalName.startsWith('io.xh.hoist')}
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
\n
        """)
    }

    private void ensureRequiredConfigsCreated() {
        Utils.configService.ensureRequiredConfigsCreated([
            xhAboutMenuConfigs: [
                valueType: 'json',
                defaultValue: [],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'AppConfigs to display in the client app About panel. Enter as a list of object of the form {"key": "configName", "label": "Display Name"}.'
            ],
            xhAppInstances: [
                valueType: 'json',
                defaultValue: [],
                clientVisible: true,
                groupName: 'xh.io',
                note: 'List of root URLs for running instances of this app across environments. Currently only used for as a convenience feature in the Admin config diff tool.'
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
            xhIdleTimeoutMins: [
                valueType: 'int',
                defaultValue: -1,
                clientVisible: true,
                groupName: 'xh.io',
                note: 'Number of minutes of inactivity before client application will enter "sleep mode", suspending background requests and prompting the user to reload to resume. Value -1 disables idle detection.'
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
            xhAdminActivityChartSize: [
                type: 'json',
                defaultValue: [:],
                local: true,
                groupName: 'xh.io',
                note: 'Admin console Client Activity chart panel sizing info.'
            ],
            xhAutoRefreshEnabled: [
                type: 'bool',
                defaultValue: true,
                groupName: 'xh.io',
                note: 'True to enable the client AutoRefreshService, which will trigger a refresh of client app data if/as specified by the xhAutoRefreshIntervals config. Note if disabled at the app level via config, this pref will have no effect.'
            ],
            xhIdleDetectionDisabled: [
                type: 'bool',
                defaultValue: false,
                local: true,
                groupName: 'xh.io',
                note: 'Set to true prevent IdleService from suspending the application due to inactivity.'
            ],
            xhShowVersionBar: [
                type: 'string',
                defaultValue: 'auto',
                groupName: 'xh.io',
                note: "Control display of Hoist footer with app version info. Options are 'auto' (show in non-prod env, or always for admins), 'always', and 'never'."
            ],
            xhTheme: [
                type: 'string',
                defaultValue: 'light',
                local: true,
                groupName: 'xh.io',
                note: 'Visual theme for the client application - "light" or "dark".'
            ]
        ])
    }

}
