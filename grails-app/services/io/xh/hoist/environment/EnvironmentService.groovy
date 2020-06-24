/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.environment

import grails.util.GrailsUtil
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

/**
 * Service with metadata describing the runtime environment of Hoist.
 */
class EnvironmentService extends BaseService {

    def configService
    def hoistGrailsPlugins

    private TimeZone _appTimeZone

    static clearCachesConfigs = ['xhAppTimeZone']

    void init() {
        _appTimeZone = calcAppTimeZone()
        super.init();
    }

    /**
     * Canonical timezone for this application.
     *
     * Used for business related dates that need to be considered and displayed consistently at all locations.
     * Not to be confused with 'serverTimeZone'.
     */
    TimeZone getAppTimeZone() {
        return _appTimeZone
    }

    /**
     * Time zone for the server/JVM running this application.
     */
    TimeZone getServerTimeZone() {
        return Calendar.instance.timeZone
    }

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
                startupTime:            Utils.startupTime,
                grailsVersion:          GrailsUtil.grailsVersion,
                javaVersion:            System.getProperty('java.version'),
                serverTimeZone:         serverTz.toZoneId().id,
                serverTimeZoneOffset:   serverTz.getOffset(now),
                appTimeZone:            appTz.toZoneId().id,
                appTimeZoneOffset:      appTz.getOffset(now)
        ]

        hoistGrailsPlugins.each {it ->
            ret[it.name + 'Version'] = it.version
        }

        def user = identityService.getAuthUser()
        if (user?.isHoistAdmin) {
            def dataSource = Utils.dataSource
            ret.databaseConnectionString = dataSource.url
            ret.databaseUser = dataSource.username
            ret.databaseCreateMode = dataSource.dbCreate
        }
        return ret
    }


    //---------------------
    // Implementation
    //---------------------
    private TimeZone calcAppTimeZone() {
        def id = configService.getString('xhAppTimeZone', 'GMT'),
            availableIDs = TimeZone.availableIDs
        if (!availableIDs.contains(id)) {
            log.error("xhAppTimeZone '$id' not recognized.  Falling back to GMT.")
        }
        return TimeZone.getTimeZone(id)
    }

    void clearCaches() {
        this._appTimeZone = calcAppTimeZone()
        super.clearCaches()
    }
}
