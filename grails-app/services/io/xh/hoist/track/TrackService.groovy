/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.util.Utils

import static io.xh.hoist.browser.Utils.getBrowser
import static io.xh.hoist.browser.Utils.getDevice
import static io.xh.hoist.json.JSONSerializer.serialize
import static io.xh.hoist.track.TrackSeverity.INFO
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static grails.async.Promises.task
import static io.xh.hoist.util.Utils.getCurrentRequest
import static java.lang.System.currentTimeMillis

/**
 * Service for tracking user activity within the application. This service provides a server-side
 * API for adding track log entries, while the client-side toolkits provide corresponding APIs
 * in Javascript. Track log entries are stored within the xh_track_log database table and are
 * viewable via the Hoist Admin Console's Activity > Tracking tab.
 *
 * The choice of which activities to track is up to application developers. Typical use-cases
 * involve logging queries and tracking if / how often a given feature is actually used.
 *
 * The `xhActivityTrackingConfig` soft-config can be used to configure this service, including
 * disabling it completely. Use the 'levels' property in this config to set the minimal severity for
 * persisting any particular message.  Entries in this list will be of the following form, where
 * the first matching entry for a message will determine the currently active severity to persist:
 *      levels: [
 *          [
 *              username: ['*' or comma-delimited list of usernames],
 *              category: ['*' or comma-delimited list of categories],
 *              severity: 'DEBUG'|'INFO'|'WARN'
 *          ],
 *          ...
 *      ]
 *
 * Separately, the `disableTrackLog` *instance* config can be used to disable only the *persistence*
 * of new track logs while leaving logging and the admin client UI active / accessible (intended for
 * local development environments).
 */
@CompileStatic
class TrackService extends BaseService {

    ConfigService configService

    /**
     * Create a new track log entry. Username, browser info, and datetime will be set automatically.
     * @param msg
     */
    void track(String msg) {
        track(msg: msg)
    }

    /**
     * Create a new track log entry. Username, browser info, and datetime will be set automatically.
     *
     *   @param entry
     *      msg {String}                - required, identifier of action being tracked
     *      category {String}           - optional, grouping category. Defaults to 'Default'
     *      correlationId {String}      - optional, correlation ID for tracking related actions
     *      data {Object}               - optional, object with related data to be serialized as JSON
     *      logData {boolean|String[]}  - optional, true or list of keys to log values from data.
     *                                    Defaults to value in `xhActivityTrackingConfig` or false.
     *                                    Note that only primitive values will be logged (nested objects
     *                                    or lists will be ignored, even if their key is specified).
     *      username {String}           - optional, defaults to currently authenticated user.
     *                                    Use this if track will be called in an asynchronous process,
     *                                    outside of a request, where username not otherwise available.
     *      impersonating {String}      - optional, defaults to username if impersonating, null if not.
     *                                    Use this if track will be called in an asynchronous process,
     *                                    outside of a request, where impersonator info not otherwise available.
     *      severity
     *         {String|TrackSeverity}   - optional, defaults to TrackSeverity.INFO.
     *      url {String}                - optional, url associated with statement
     *      timestamp {long}            - optional, time associated with start of action.  Defaults to current time.
     *      elapsed {int}               - optional, duration of action in millis
     */
    void track(Map entry) {
        trackAll([entry])
    }

    /**
     * Record a collection of track entries.
     *
     * @param entries -- List of maps containing data for individual track messages.
     *      See track() for information on the form of each entry.
     */
    void trackAll(Collection<Map> entries) {
        if (!enabled) {
            logTrace("Tracking disabled via config.")
            return
        }

        // Always fail quietly, and never interrupt real work.
        try {
            // Normalize data within thread to gather context
            entries = entries.collect {prepareEntry(it)}

            // Persist and log on new thread to avoid delay.
            task {
                TrackLog.withTransaction {
                    entries.each {
                        try {
                            persistEntry(it)
                            logEntry(it)
                        } catch (Exception e) {
                            logError('Exception writing track log', e)
                        }
                    }
                }
            }
        } catch (Exception e) {
            logError('Exception writing track log', e)
        }
    }

    Boolean getEnabled() {
        return conf.enabled == true
    }

    Map getConf() {
        return configService.getMap('xhActivityTrackingConfig')
    }

    //-------------------------
    // Implementation
    //-------------------------
    private Map prepareEntry(Map entry) {
        String userAgent = currentRequest?.getHeader('User-Agent')
        return [
            // From submission
            username      : entry.username ?: authUsername,
            impersonating : entry.impersonating ?: (identityService.isImpersonating() ? username : null),
            category      : entry.category ?: 'Default',
            correlationId : entry.correlationId,
            msg           : entry.msg,
            elapsed       : entry.elapsed,
            severity      : parseSeverity(entry.severity),
            data          : entry.data ? serialize(entry.data) : null,
            rawData       : entry.data,
            url           : entry.url,
            appVersion    : entry.appVersion ?: Utils.appVersion,
            timestamp     : entry.timestamp ?: currentTimeMillis(),


            // From request/context
            instance      : ClusterService.instanceName,
            appEnvironment: Utils.appEnvironment,
            userAgent     : userAgent,
            browser       : getBrowser(userAgent),
            device        : getDevice(userAgent)
        ]
    }

    private void logEntry(Map entry) {
        // Log core info,
        String name = entry.username
        if (entry.impersonating) name += " (as ${entry.impersonating})"
        Map<String, Object> msgParts = [
            _user         : name,
            _category     : entry.category,
            _msg          : entry.msg,
            _correlationId: entry.correlationId,
            _elapsedMs    : entry.elapsed,
        ].findAll { it.value != null } as Map<String, Object>

        // Log app data, if requested/configured.
        def data = entry.rawData,
            logData = entry.logData
        if (data && (data instanceof Map)) {
            logData = logData != null
                ? logData
                : conf.logData != null
                ? conf.logData
                : false

            if (logData) {
                Map<String, Object> dataParts = data as Map<String, Object>
                dataParts = dataParts.findAll { k, v ->
                    (logData === true || (logData as List).contains(k)) &&
                        !(v instanceof Map || v instanceof List)
                }
                msgParts.putAll(dataParts)
            }
        }

        logInfo(msgParts)
    }

    private void persistEntry(Map entry) {
        if (getInstanceConfig('disableTrackLog') == 'true' ||
            getActiveSeverity(entry) > parseSeverity(entry.severity)
        ) return

        String data = entry.data
        if (data?.size() > (conf.maxDataLength as Integer)) {
            logTrace(
                "Track log with message [$entry.msg] includes ${data.size()} chars of JSON data",
                "exceeds limit of ${conf.maxDataLength}",
                "data will not be persisted"
            )
            entry.data = null
        }

        TrackLog tl = new TrackLog(entry)
        tl.dateCreated = new Date(entry.timestamp as Long)
        tl.save()
    }

    private TrackSeverity getActiveSeverity(Map entry) {
        def username = entry.username as String,
            cat = entry.category as String,
            levels = (conf.levels ?: []) as List<Map>

        def match = levels.find {
            def levUser = it.username as String,
                levCat = it.category as String

            (levUser == '*' || levUser.contains(username)) && (levCat == '*' || levCat.contains(cat))
        }
        return match ? parseSeverity(match.severity) : INFO
    }

    private TrackSeverity parseSeverity(Object severity) {
        if (severity instanceof TrackSeverity) return severity
        if (!severity) return INFO
        try {
            return TrackSeverity.valueOf(severity.toString().toUpperCase().trim())
        } catch (IllegalArgumentException ex) {
            return INFO
        }
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhActivityTrackingConfig')
    ]}
}
