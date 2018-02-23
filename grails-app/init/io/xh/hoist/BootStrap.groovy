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
                        clientVisible: true
                ],
                xhAppVersionCheckEnabled: [
                        valueType: 'bool',
                        defaultValue: false
                ],
                xhAppVersionCheckSecs: [
                        valueType: 'int',
                        defaultValue: 30,
                        clientVisible: true
                ],
                xhEmailDefaultDomain: [
                        valueType: 'string',
                        defaultValue: 'xh.io'
                ],
                xhEmailDefaultSender: [
                        valueType: 'string',
                        defaultValue: 'support@xh.io'
                ],
                xhEmailFilter: [
                        valueType: 'string',
                        defaultValue: 'none'
                ],
                xhEmailOverride: [
                        valueType: 'string',
                        defaultValue: 'none'
                ],
                xhEmailSupport: [
                        valueType: 'string',
                        defaultValue: 'support@xh.io'
                ],
                xhIdleTimeoutMins: [
                        valueType: 'int',
                        defaultValue: 180,
                        clientVisible: true
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
                        ]
                ],
                xhMonitorEmailRecipients: [
                        valueType: 'string',
                        defaultValue: 'support@xh.io'
                ]
        ])
    }
    
}
