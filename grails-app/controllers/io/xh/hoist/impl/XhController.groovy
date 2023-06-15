/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.impl

import groovy.transform.CompileStatic
import io.xh.hoist.BaseController
import io.xh.hoist.alertbanner.AlertBannerService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.clienterror.ClientErrorService
import io.xh.hoist.exception.NotFoundException
import io.xh.hoist.exception.SessionMismatchException
import io.xh.hoist.export.GridExportImplService
import io.xh.hoist.feedback.FeedbackService
import io.xh.hoist.json.JSONParser
import io.xh.hoist.jsonblob.JsonBlobService
import io.xh.hoist.pref.PrefService
import io.xh.hoist.security.AccessAll
import io.xh.hoist.track.TrackService
import io.xh.hoist.environment.EnvironmentService
import io.xh.hoist.user.BaseUserService
import io.xh.hoist.util.Utils

import static io.xh.hoist.json.JSONParser.parseObject

@AccessAll
@CompileStatic
class XhController extends BaseController {

    AlertBannerService alertBannerService
    ClientErrorService clientErrorService
    ConfigService configService
    FeedbackService feedbackService
    GridExportImplService gridExportImplService
    JsonBlobService jsonBlobService
    PrefService prefService
    TrackService trackService
    EnvironmentService environmentService
    BaseUserService userService

    //------------------------
    // Identity / Auth
    //------------------------
    def authStatus() {
        renderJSON(authenticated: authUser != null)
    }

    def getIdentity() {
        renderJSON(identityService.clientConfig)
    }

    def login(String username, String password) {
        def success = identityService.login(username, password)
        renderJSON(success: success)
    }

    def logout() {
        def success = identityService.logout()
        renderJSON(success: success)
    }


    //------------------------
    // Admin Impersonation
    // Protected internally by identity service.
    //------------------------
    def impersonationTargets() {
        def targets = userService.impersonationTargetsForUser(authUser)
        renderJSON(targets.collect{[username: it.username]})
    }

    def impersonate(String username) {
        identityService.impersonate(username)
        renderJSON(success: true)
    }

    def endImpersonate() {
        identityService.endImpersonate()
        renderJSON(success: true)
    }


    //------------------------
    // Tracking
    //------------------------
    def track(String category, String msg, String data, int elapsed, String severity) {
        if (!trackService.enabled) {
            renderJSON(success: false, error: 'Activity Tracking disabled via config')
            return
        }

        ensureClientUsernameMatchesSession()
        trackService.track(
            category: safeEncode(category),
            msg: safeEncode(msg),
            data: data ? JSONParser.parseObjectOrArray(safeEncode(data)) : null,
            elapsed: elapsed,
            severity: safeEncode(severity)
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
        ensureClientUsernameMatchesSession()
        renderJSON(prefService.clientConfig)
    }

    def setPrefs(String updates) {
        ensureClientUsernameMatchesSession()

        Map prefs = parseObject(updates)
        prefs.each {k, value ->
            String key = k.toString()
            if (value instanceof Map) {
                prefService.setMap(key, value)
            } else if (value instanceof List) {
                prefService.setList(key, value)
            } else {
                prefService.setPreference(key, value.toString())
            }
        }

        def ret = prefService.getLimitedClientConfig(prefs.keySet() as List)

        renderJSON(preferences: ret)
    }

    def migrateLocalPrefs(String updates) {
        ensureClientUsernameMatchesSession()
        Map prefs = JSONParser.parseObject(updates)
        prefs.each { k, value ->
            String key = k.toString()
            try {
                if (!prefService.isUnset(key)) return
                if (value instanceof Map) {
                    prefService.setMap(key, value)
                } else if (value instanceof List) {
                    prefService.setList(key, value)
                } else {
                    prefService.setPreference(key, value.toString())
                }
            } catch (e) {
                logError("Failed to recover pref '$key'", e)
            }
        }
        renderJSON(success: true)
    }

    def clearPrefs() {
        ensureClientUsernameMatchesSession()
        prefService.clearPreferences()
        renderJSON(success: true)
    }

    //------------------------
    // Json Blobs
    //------------------------
    def getJsonBlob(String token) {
        def ret = jsonBlobService.get(token)
        renderJSON(ret.formatForClient())
    }

    def listJsonBlobs(String type, boolean includeValue) {
        def ret = jsonBlobService.list(type)
        renderJSON(ret*.formatForClient(includeValue))
    }

    def createJsonBlob(String data) {
        def ret = jsonBlobService.create(parseObject(data))
        renderJSON(ret.formatForClient())
    }

    def updateJsonBlob(String token, String update) {
        def ret = jsonBlobService.update(token, parseObject(update))
        renderJSON(ret.formatForClient())
    }

    def archiveJsonBlob(String token) {
        def ret = jsonBlobService.archive(token)
        renderJSON(ret.formatForClient())
    }


    //------------------------
    // Export
    //------------------------
    // The 'params' is a JSON encoded string, uploaded using multipart/form-data to be treated as a file. We must read
    // its content from the inputStream, and then parse the JSON to get usable params for GridExportImplService.
    def export() {
        def inputStream = request.getPart('params').inputStream,
            data = parseObject(inputStream),
            ret = gridExportImplService.getBytesForRender(data)
        render(ret)
    }


    //------------------------
    // Environment
    //------------------------
    def environment() {
        renderJSON(environmentService.getEnvironment())
    }

    def version() {
        def shouldUpdate = configService.getBool('xhAppVersionCheckEnabled')
        renderJSON (
            appVersion: Utils.appVersion,
            appBuild: Utils.appBuild,
            shouldUpdate: shouldUpdate
        )
    }

    //------------------------
    // Client Errors
    //------------------------
    def submitError(String msg, String error, String appVersion, String url, boolean userAlerted) {
        ensureClientUsernameMatchesSession()
        clientErrorService.submit(
            safeEncode(msg),
            safeEncode(error),
            safeEncode(appVersion),
            safeEncode(url),
            userAlerted
        )
        renderJSON(success: true)
    }

    //------------------------
    // Feedback
    //------------------------
    def submitFeedback(String msg, String appVersion) {
        ensureClientUsernameMatchesSession()
        feedbackService.submit(
            safeEncode(msg),
            safeEncode(appVersion)
        )
        renderJSON(success: true)
    }

    //----------------------
    // Alert Banner
    //----------------------
    def alertBanner() {
        renderJSON(alertBannerService.alertBanner)
    }

    //-----------------------
    // Misc
    //-----------------------
    /**
     * Returns the timezone offset for a given timezone ID.
     * While abbrevs (e.g. 'GMT', 'PST', 'UTC+04') are supported, fully qualified IDs (e.g.
     * 'Europe/London', 'America/New_York') are preferred, as these account for daylight savings.
     */
    def getTimeZoneOffset(String timeZoneId) {
        // Validate ID, as getTimeZone() defaults to GMT if not recognized.
        def availableIds = TimeZone.getAvailableIDs()
        if (!availableIds.contains(timeZoneId)) {
            throw new NotFoundException("Unknown timeZoneId")
        }
        def tz = TimeZone.getTimeZone(timeZoneId)
        renderJSON([offset: tz.getOffset(System.currentTimeMillis())])
    }

    def notFound() {
        throw new NotFoundException()
    }

    //------------------------
    // Implementation
    //------------------------
    /**
     * Check to validate that the client's expectation of the current user matches the active user
     * actually present within the session. Should be called prior to any operations in this
     * controller that rely on or return / modify data specific to a particular user.
     *
     * Note this is *not* a security check - access control and validation of user operations are
     * the responsibilities of the auth framework and relevant services. This is intended to avoid
     * edge-case bugs where the server-side login has changed but the app within a client browser
     * is not yet aware - e.g. calls made on page unload during the start of an impersonation session.
     */
    private void ensureClientUsernameMatchesSession() {
        def clientUsername = params.clientUsername

        if (!clientUsername) {
            throw new SessionMismatchException("Unable to confirm match between client and session user.")
        } else if (clientUsername != username) {
            throw new SessionMismatchException("The reported clientUsername does not match current session user.")
        }
    }
}
