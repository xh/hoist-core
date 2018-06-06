/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.impl

import grails.plugins.GrailsPlugin
import grails.util.GrailsUtil
import grails.util.Holders
import groovy.transform.CompileStatic
import io.xh.hoist.BaseController
import io.xh.hoist.config.ConfigService
import io.xh.hoist.dash.DashboardService
import io.xh.hoist.clienterror.ClientErrorService
import io.xh.hoist.export.GridExportImplService
import io.xh.hoist.feedback.FeedbackService
import io.xh.hoist.json.JSON
import io.xh.hoist.pref.PrefService
import io.xh.hoist.security.AccessAll
import io.xh.hoist.user.BaseUserService
import io.xh.hoist.track.TrackService
import io.xh.hoist.util.Utils
import org.grails.web.json.JSONObject

@AccessAll
@CompileStatic
class HoistImplController extends BaseController {

    TrackService trackService
    PrefService prefService
    ClientErrorService clientErrorService
    ConfigService configService
    GridExportImplService gridExportImplService
    DashboardService dashboardService
    FeedbackService feedbackService
    BaseUserService userService

    //------------------------
    // Identity
    //------------------------
    def getIdentity() {
        renderJSON(identityService.clientConfig)
    }

    def impersonationTargets() {
        def usernames = userService.list(true).collect{ [username: it.username] }
        renderJSON(usernames)
    }

    def impersonate(String username) {
        identityService.impersonate(username)
        renderJSON(success: true)
    }

    def endImpersonate() {
        identityService.endImpersonate()
        renderJSON(success: true)
    }

    def logout() {
        identityService.logout()
        renderJSON(success: true)
    }

    //------------------------
    // Tracking
    //------------------------
    def track() {
        trackService.track(
                category: params.category,
                msg: params.msg,
                data: params.data ? JSON.parse((String) params.data) : null,
                elapsed: params.elapsed,
                severity: params.severity
        )
        renderJSON(success: true)
    }

    //------------------------
    // Config
    //------------------------
    def getConfig() {
        renderJSON(configService.clientConfig)
    }

    //------------------------
    // Preferences
    //------------------------
    def getPrefs() {
        renderJSON(prefService.clientConfig)
    }

    def setPrefs(String updates) {
        JSONObject prefs = (JSONObject) JSON.parse(updates)
        prefs.each {k, value ->
            String key = k.toString()
            if (value instanceof JSONObject) {
                prefService.setJSON(key, value)
            } else {
                prefService.setPreference(key, value.toString())
            }
        }
        def ret = prefService.getLimitedClientConfig(prefs.keySet() as List)
        renderJSON(preferences: ret)
    }

    def clearPrefs() {
        prefService.clearPreferences()
        renderJSON(success: true)
    }

    //------------------------
    // Export
    //------------------------
    def export(String filename, String filetype, String rows, String meta) {
        def ret = gridExportImplService.getBytesForRender(filename, filetype, rows, meta)
        render(ret)
    }

    //------------------------
    // Environment
    //------------------------
    def environment() {
        def ret = [
                appCode:                Utils.appCode,
                appName:                Utils.appName,
                appVersion:             Utils.appVersion,
                appEnvironment:         Utils.appEnvironment.toString(),
                grailsVersion:          GrailsUtil.grailsVersion,
                javaVersion:            System.getProperty('java.version')
        ]

        hoistGrailsPlugins.each {it ->
            ret[it.name + 'Version'] = it.version
        }

        renderJSON(ret)
    }

    def version() {
        def shouldUpdate = configService.getBool('xhAppVersionCheckEnabled')

        renderJSON (
                appVersion: Utils.appVersion,
                shouldUpdate: shouldUpdate
        )
    }

    //------------------------
    // Dashboards
    //------------------------
    def getDashboard(String appCode) {
        renderJSON(dashboardService.get(appCode))
    }

    def saveDashboard(String appCode, String definition) {
        renderJSON(dashboardService.save(appCode, definition))
    }

    def deleteUserDashboard(String appCode) {
        dashboardService.deleteUserInstance(appCode)
        renderJSON(success: true)
    }

    //------------------------
    // Client Errors
    //------------------------
    def submitError(String msg, String error, String appVersion) {
        clientErrorService.submit(msg, error, appVersion)
        renderJSON(success: true)
    }

    //------------------------
    // Feedback
    //------------------------
    def submitFeedback(String msg, String appVersion) {
        feedbackService.submit(msg, appVersion)
        renderJSON(success: true)
    }

    //------------------------
    // Timezone
    //
    // Returns the timezone offset for a given timezone id. While abbreviations (e.g. 'GMT', 'PST', 'UTC+04') are supported,
    // fully qualified timezone ids (e.g. 'Europe/London', 'America/New_York') are preferred, as these account for daylight savings.
    // Note we explicitly check against the available ids - this is because TimeZone.getTimeZone() defaults to GMT if not recognized.
    //------------------------
    def getTimeZoneOffset(String timeZoneId) {
        def availableIds = TimeZone.getAvailableIDs()
        if (!availableIds.contains(timeZoneId)) {
            throw new RuntimeException('Timezone ID ' + timeZoneId + ' not recognized')
        }
        def tz = TimeZone.getTimeZone(timeZoneId)
        renderJSON([offset: tz.getOffset(System.currentTimeMillis())])
    }


    //------------------------
    // Implementation
    //------------------------
    private Collection<GrailsPlugin> getHoistGrailsPlugins() {
        return Holders.currentPluginManager().allPlugins.findAll{it.name.startsWith('hoist')}
    }

}
