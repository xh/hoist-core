/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.impl

import groovy.transform.CompileStatic
import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.exception.NotFoundException
import io.xh.hoist.exception.SessionMismatchException
import io.xh.hoist.export.GridExportImplService
import io.xh.hoist.json.JSONParser
import io.xh.hoist.jsonblob.JsonBlobService
import io.xh.hoist.pref.PrefService
import io.xh.hoist.pref.Preference
import io.xh.hoist.security.AccessAll
import io.xh.hoist.security.BaseAuthenticationService
import io.xh.hoist.track.TrackService
import io.xh.hoist.environment.EnvironmentService
import io.xh.hoist.user.BaseUserService
import io.xh.hoist.util.Utils
import io.xh.hoist.view.ViewService

import static io.xh.hoist.json.JSONParser.parseObject

@AccessAll
@CompileStatic
class XhController extends BaseController {

    ConfigService configService
    GridExportImplService gridExportImplService
    JsonBlobService jsonBlobService
    PrefService prefService
    ViewService viewService
    TrackService trackService
    EnvironmentService environmentService
    BaseUserService userService
    ClusterService clusterService


    //------------------------
    // Identity / Auth
    //------------------------
    def authStatus() {
        renderJSON(authenticated: authUser != null)
    }

    /** Whitelisted endpoint to return auth-related settings for client bootstrap. */
    def authConfig() {
        def svc = Utils.appContext.getBean(BaseAuthenticationService)
        renderJSON(svc.clientConfig)
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
        renderSuccess()
    }

    def endImpersonate() {
        identityService.endImpersonate()
        renderSuccess()
    }

    //------------------------
    // Tracking
    //------------------------
    def track() {
        ensureClientUsernameMatchesSession()
        def payload = parseRequestJSON([safeEncode: true]),
            entries =  payload.entries as List
        trackService.trackAll(entries)
        renderSuccess()
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

    def setPrefs() {
        ensureClientUsernameMatchesSession()

        def prefs = parseRequestJSON()
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
        renderSuccess()
    }

    def clearUserState() {
        ensureClientUsernameMatchesSession()
        Preference.withNewTransaction {
            prefService.clearPreferences()
            viewService.clearAllState()
        }
        renderSuccess()
    }

    /** @deprecated.  Required by hoist-react <=v.75 */
    def clearPrefs() {
        ensureClientUsernameMatchesSession()
        prefService.clearPreferences()
        renderSuccess()
    }

    //------------------------
    // Json Blobs
    //------------------------
    def getJsonBlob(String token) {
        def ret = jsonBlobService.get(token)
        renderJSON(ret.formatForClient())
    }

    def findJsonBlob(String type, String name, String owner) {
        def ret = jsonBlobService.find(type, name, owner)
        renderJSON(ret?.formatForClient())
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

    def createOrUpdateJsonBlob(String type, String name, String update) {
        def ret = jsonBlobService.createOrUpdate(type, name, parseObject(update))
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

    def environmentPoll() {
        renderJSON(environmentService.environmentPoll())
    }


    //-----------------------
    // Misc
    //-----------------------
    /**
     * Whitelisted (pre-auth) endpoint with minimal app identifier, for uptime/reachability checks.
     * Also reachable via legacy `/ping` alias (via `UrlMappings`), but prefer `/xh/ping`.
     */
    def ping() {
        renderJSON(
            appCode: Utils.appCode,
            timestamp: System.currentTimeMillis(),
            success: true
        )
    }

    /**
     * Whitelisted (pre-auth) endpoint with minimal app identifier and version info.
     */
    def version() {
        renderJSON(
            appCode: Utils.appCode,
            appVersion: Utils.appVersion,
            appBuild: Utils.appBuild
        )
    }

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

    /**
     * Utility to echo all headers received on the request. Useful in particular for verifying
     * headers (e.g. `jespa_connection_id`) that are installed by or must pass through multiple
     * ingresses/load balancers.
     */
    def echoHeaders() {
        renderJSON(request.headerNames.toList().collectEntries { [it, request.getHeader(it)] })
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
