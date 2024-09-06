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
import io.xh.hoist.alertbanner.AlertBannerService
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
    AlertBannerService alertBannerService

    private TimeZone _appTimeZone

    static clearCachesConfigs = ['xhAppTimeZone', 'xhEnvPollConfig']

    /**
     * Official TimeZone for this application - e.g. the zone of the head office or trading center.
     *
     * Used to format or parse business related dates that need to be considered and displayed in a
     * consistent, agreed upon TimeZone, regardless of the location or zone of a client browser.
     * Not to be confused with `serverTimeZone` below.
     */
    TimeZone getAppTimeZone() {
        return _appTimeZone ?= calcAppTimeZone()
    }

    /** TimeZone of the server/JVM running this application. */
    TimeZone getServerTimeZone() {
        return Calendar.instance.timeZone
    }

    /** Full bundle of environment-related metadata, for serialization to JS clients. */
    Map getEnvironment() {
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
                alertBanner:            alertBannerService.alertBanner,
                pollConfig:             configService.getMap('xhEnvPollConfig')
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
     * Report server version and instance identity to the client.
     * Designed to be called frequently by client. Should be minimal and highly optimized.
     */
    Map environmentPoll() {
        return [
            appCode     : Utils.appCode,
            appVersion  : Utils.appVersion,
            appBuild    : Utils.appBuild,
            instanceName: clusterService.instanceName,
            alertBanner : alertBannerService.alertBanner,
            pollConfig  : configService.getMap('xhEnvPollConfig')
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
        _appTimeZone = null
        super.clearCaches()
    }
}
