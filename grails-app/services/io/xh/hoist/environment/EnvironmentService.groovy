/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.environment

import grails.plugins.GrailsPlugin
import grails.util.GrailsUtil
import grails.util.Holders
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.util.Utils
import io.xh.hoist.websocket.WebSocketService


/**
 * Service with metadata describing the runtime environment of Hoist and this application.
 * For the AppEnvironment (e.g. Development/Production), reference `Utils.appEnvironment`.
 */
class EnvironmentService extends BaseService {

    ConfigService configService
    WebSocketService webSocketService

    private TimeZone _appTimeZone

    static clearCachesConfigs = ['xhAppTimeZone']

    void init() {
        _appTimeZone = calcAppTimeZone()
    }

    /**
     * Official TimeZone for this application - e.g. the zone of the head office or trading center.
     *
     * Used to format or parse business related dates that need to be considered and displayed in a
     * consistent, agreed upon TimeZone, regardless of the location or zone of a client browser.
     * Not to be confused with `serverTimeZone` below.
     */
    TimeZone getAppTimeZone() {
        return _appTimeZone
    }

    /** TimeZone of the server/JVM running this application. */
    TimeZone getServerTimeZone() {
        return Calendar.instance.timeZone
    }

    /**
     * Returns environment-related metadata, varying based on the current authenticated user.
     *
     * If there is not an authenticated user, a minimal summary object will be returned.
     *
     * Otherwise, the return payload will include additional details regarding this application,
     * runtime, and libraries, along with current `xhEnvPollingConfig` settings to control client
     * polling to detect changes to the connected instance or the application version.
     */
    Map getEnvironment() {
        def authUser = getAuthUser()
        if (!authUser) {
            return getEnvironmentSummary()
        }

        def serverTz = serverTimeZone,
            appTz = appTimeZone,
            now = System.currentTimeMillis()

        def ret = [
                appCode:                Utils.appCode,
                appName:                Utils.appName,
                appVersion:             Utils.appVersion,
                appBuild:               Utils.appBuild,
                appEnvironment:         Utils.appEnvironment.toString(),
                grailsVersion:          GrailsUtil.grailsVersion,
                javaVersion:            System.getProperty('java.version'),
                serverTimeZone:         serverTz.toZoneId().id,
                serverTimeZoneOffset:   serverTz.getOffset(now),
                appTimeZone:            appTz.toZoneId().id,
                appTimeZoneOffset:      appTz.getOffset(now),
                webSocketsEnabled:      webSocketService.enabled,
                instanceName:           clusterService.instanceName,
                pollingConfig:          configService.getMap('xhEnvPollingConfig')
        ]

        hoistGrailsPlugins.each {it ->
            ret[it.name + 'Version'] = it.version
        }

        if (authUser.isHoistAdminReader) {
            def dataSource = Utils.dataSourceConfig
            ret.databaseConnectionString = dataSource.url
            ret.databaseUser = dataSource.username
            ret.databaseCreateMode = dataSource.dbCreate
        }

        return ret
    }

    /**
     * Returns deliberately minimal environment metadata, suitable for unauthenticated requests.
     */
    Map getEnvironmentSummary() {
        [
            appCode: Utils.appCode,
            appVersion: Utils.appVersion,
            appBuild: Utils.appBuild,
            appEnvironment: Utils.appEnvironment.toString(),
            instanceName: clusterService.instanceName
        ]
    }


    //---------------------
    // Implementation
    //---------------------
    private TimeZone calcAppTimeZone() {
        def defaultZone = 'UTC',
            configZoneId = configService.getString('xhAppTimeZone')

        if (!TimeZone.availableIDs.contains(configZoneId)) {
            log.error("Invalid xhAppTimeZone config: '$configZoneId' not a valid ZoneId - will fall back to $defaultZone.")
            configZoneId = defaultZone;
        }

        return TimeZone.getTimeZone(configZoneId)
    }

    private Collection<GrailsPlugin> getHoistGrailsPlugins() {
        return Holders.currentPluginManager().allPlugins.findAll{it.name.startsWith('hoist')}
    }

    void clearCaches() {
        this._appTimeZone = calcAppTimeZone()
        super.clearCaches()
    }
}
