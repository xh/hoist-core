/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.util.Holders
import io.xh.hoist.util.Utils

class BootStrap {

    def init = {servletContext ->
        checkEnvironment()
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
    private void checkEnvironment() {
        def supportedEnvironments = Utils.supportedEnvironments,
            appEnvironment = Utils.appEnvironment.toString()

        if (!supportedEnvironments.contains(appEnvironment)) {
            throw new RuntimeException("Environment not supported for this application: ${appEnvironment}")
        }
    }

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
          Extremely Heavy Industries - http://xh.io
\n
        """)
    }

    private void ensureRequiredConfigsCreated() {
        Utils.configService.ensureRequiredConfigsCreated([
                xhAboutMenuConfigs: [
                        valueType: 'json',
                        defaultValue: [],
                        clientVisible: true,
                        note: 'Describes which soft configs to display in about panel.'
                ],
                xhAppInstances: [
                        valueType: 'json',
                        defaultValue: [],
                        clientVisible: true
                ],
                xhAppVersionCheckEnabled: [
                        valueType: 'bool',
                        defaultValue: false,
                        note: 'Enable automatic version checking by the client application to show banner on an update.'
                ],
                xhAppVersionCheckSecs: [
                        valueType: 'int',
                        defaultValue: 30,
                        clientVisible: true,
                        note: 'Frequency with which the version of the app should be checked.  -1 indicates should be disabled'
                ],
                xhEmailDefaultDomain: [
                        valueType: 'string',
                        defaultValue: 'xh.io'
                ],
                xhEmailDefaultSender: [
                        valueType: 'string',
                        defaultValue: 'support@xh.io',
                        note: 'Email address for Hoist emailService to use as default sender address.'
                ],
                xhEmailFilter: [
                        valueType: 'string',
                        defaultValue: 'none',
                        note: 'Comma-separated list of email addresses to which Hoist emailService can send mail. For testing / dev purposes. Value "none" does not filter recipients.'
                ],
                xhEmailOverride: [
                        valueType: 'string',
                        defaultValue: 'none',
                        note: 'Email address to which Hoist emailService should send all mail, regardless of specified recipient. For testing / dev purposes.Special value "none" disables any override.'
                ],
                xhEmailSupport: [
                        valueType: 'string',
                        defaultValue: 'none',
                        note: 'Mail to which support and feedback should be sent.'
                ],
                xhIdleTimeoutMins: [
                        valueType: 'int',
                        defaultValue: 180,
                        clientVisible: true,
                        note: 'Number of minutes of inactivity before IdleService will  put application to sleep.  -1 indicates should be disabled.'
                ],
                xhLogArchiveConfig: [
                        valueType: 'json',
                        defaultValue: [
                                archiveAfterDays: 30,
                                archiveDirectory: 'archive'
                        ]
                ],
                xhMonitorConfig: [
                        valueType: 'json',
                        defaultValue: [
                                monitorRefreshMins: 10,
                                failNotifyThreshold: 2,
                                warnNotifyThreshold: 5,
                                monitorStartupDelayMins: 1,
                                monitorRepeatNotifyMins: 60
                        ],
                        note: 'Describes behavior of server side application monitoring and notifications. failNotifyThreshold and warnNotifyThreshold are the number of monitor refresh cycles a monitor will need to be in said status to trigger \'alertMode\'.'
                ],
                xhMonitorEmailRecipients: [
                        valueType: 'string',
                        defaultValue: 'none'
                ]
        ])
    }

    private void ensureRequiredPrefsCreated() {
        Utils.prefService.ensureRequiredPrefsCreated([
                xhAdminActivityChartSize: [
                        type: 'json',
                        defaultValue: {},
                        local: true
                ],
                xhForceEnvironmentFooter: [
                        type: 'bool',
                        defaultValue: false
                ],
                xhTheme: [
                        type: 'string',
                        defaultValue: 'dark',
                        local: true
                ]
        ])
    }

}
