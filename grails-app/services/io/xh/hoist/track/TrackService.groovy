/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import groovy.transform.CompileStatic
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.util.RateMonitor
import io.xh.hoist.util.Utils

import static io.xh.hoist.browser.Utils.getBrowser
import static io.xh.hoist.browser.Utils.getDevice
import static io.xh.hoist.json.JSONSerializer.serialize
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static grails.async.Promises.task
import static io.xh.hoist.util.Utils.getCurrentRequest
import static io.xh.hoist.util.DateTimeUtils.MINUTES

/**
 * Service for tracking user activity within the application. This service provides a server-side
 * API for adding track log entries, while the client-side toolkits provide corresponding APIs
 * in Javascript. Track log entries are stored within the xh_track_log database table and are
 * viewable via the Hoist Admin Console's Activity > Tracking tab.
 *
 * The choice of which activities to track is up to application developers. Typical use-cases
 * involve logging queries and tracking if / how often a given feature is actually used.
 *
 * When a TrackLog entry is received by the server, the server-side event 'xhTrackReceived' will
 * be published on the cluster. Services in the cluster may use this for notification or monitoring
 * of app activity.
 *
 * The `xhActivityTrackingConfig` soft-config can be used to configure this service, including
 * disabling it completely. Use the 'levels' property in this config to set the minimal severity for
 * persisting any particular message. Entries in this list will be of the following form, where
 * the first matching entry for a message will determine the currently active severity to persist:
 *
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

    static clearCachesConfigs = ['xhActivityTrackingConfig']
    ConfigService configService
    TrackLoggingService trackLoggingService

    private final boolean persistenceDisabled = getInstanceConfig('disableTrackLog') == 'true'
    private boolean rateLimitActive = false
    private RateMonitor rateMonitor

    void init() {
        rateMonitor = createRateMonitor()
        super.init()
    }

    /**
     * Create a new track log entry.
     */
    @NamedVariant
    void track(

        // Core parameters
        /** A concise description of whatever action being tracked. */
        @NamedParam(required = true) String msg,
        /** Category for organizing and querying over related actions. */
        @NamedParam String category = 'Default',
        /** The severity of this message. Defaults to TrackSeverity.INFO.*/
        @NamedParam Object severity = TrackSeverity.INFO,
        /** Additional data payload to store with the track log (will be serialized as JSON). */
        @NamedParam Object data = null,
        /** A list of keys from the data object to log, `true` to log all key/values. Default false. */
        @NamedParam Object logData = null,
        /** Optional Correlation Id */
        @NamedParam String correlationId = null,
        /** Time associated with the start of the action. Defaults to now. */
        @NamedParam Long timestamp = null,
        /** Duration of the tracked action in milliseconds, if applicable. */
        @NamedParam Integer elapsed = null,

        // For setting on async processes, not typically used.
        /**
         * The username of the user associated with this action. Defaults to currently authenticated
         * user, but can be specified to capture user when called from an async process, outside the
         * context of a request.
         */
        @NamedParam String username = null,
        /**
         * If impersonating, the username of the user being impersonated. Set automatically when
         * applicable, provided here for async usage outside of a request (as with `username`).
         */
        @NamedParam String impersonating = null,

        // From client-side, for internal use.
        /** Client-side tabId, maintained by hoist-react for the life of a browser tab. */
        @NamedParam String tabId = null,
        /** Client-side loadId, maintained by hoist-react for each load of the client app. */
        @NamedParam String loadId = null,
        /** Client-side url of the app performing the activity. */
        @NamedParam String url = null
    ) {
        trackAll([
            [
                msg          : msg,
                category     : category,
                correlationId: correlationId,
                data         : data,
                logData      : logData,
                username     : username,
                impersonating: impersonating,
                severity     : severity,
                url          : url,
                timestamp    : timestamp,
                elapsed      : elapsed,
                tabId        : tabId,
                loadId       : loadId
            ]
        ] as Collection<Map>)
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
        applyRateLimiting(entries);

        def doPersist = !persistenceDisabled && !rateLimitActive,
            topic = getTopic('xhTrackReceived')

        // Always fail quietly, and never interrupt real work.
        try {
            // Normalize data within thread to gather context
            entries = entries.collect { prepareEntry(it) }

            def processFn = {
                entries.each {
                    try {
                        TimestampedLogEntry logEntry = createLogEntry(it)
                        trackLoggingService.logEntry(logEntry)

                        TrackLog tl = createTrackLog(it)
                        if (doPersist && isSeverityActive(tl)) {
                            tl.save()
                        }
                        topic.publishAsync(tl)
                    } catch (Exception e) {
                        logError('Exception recording track log', e)
                    }
                }
            }

            // Process on new thread to avoid delay, only create transaction if needed.
            doPersist ?
                task { TrackLog.withTransaction(processFn) }:
                task(processFn)

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
            impersonating : entry.impersonating ?: (identityService.impersonating ? username : null),
            category      : entry.category ?: 'Default',
            correlationId : entry.correlationId,
            msg           : entry.msg,
            elapsed       : entry.elapsed,
            severity      : TrackSeverity.parse(entry.severity as String),
            data          : entry.data ? serialize(entry.data) : null,
            rawData       : entry.data,
            url           : entry.url?.toString()?.take(500),
            appVersion    : entry.appVersion ?: Utils.appVersion,
            loadId        : entry.loadId,
            tabId         : entry.tabId,
            dateCreated   : entry.timestamp ? new Date(entry.timestamp as Long) : new Date(),


            // From request/context
            instance      : ClusterService.instanceName,
            appEnvironment: Utils.appEnvironment,
            userAgent     : userAgent,
            browser       : getBrowser(userAgent),
            device        : getDevice(userAgent)
        ]
    }

    private TrackLog createTrackLog(Map entry) {
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
        tl.dateCreated = entry.dateCreated as Date
        return tl
    }

    private TimestampedLogEntry createLogEntry(Map entry) {
        // Log core info,
        String name = entry.username
        Date dateCreated = entry.dateCreated as Date

        if (entry.impersonating) name += " (as ${entry.impersonating})"
        Map<String, Object> message = [
            _timestamp    : dateCreated.format('yyyy-MM-dd HH:mm:ss.SSS'),
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
                message.putAll(dataParts)
            }
        }
        return new TimestampedLogEntry(message: message, timestamp: dateCreated.time)
    }

    private boolean isSeverityActive(TrackLog tl) {
        def username = tl.username as String,
            cat = tl.category as String,
            levels = (conf.levels ?: []) as List<Map>

        def match = levels.find {
            def levUser = it.username as String,
                levCat = it.category as String

            (levUser == '*' || levUser.contains(username)) && (levCat == '*' || levCat.contains(cat))
        }

        return TrackSeverity.parse(match?.severity as String) <= TrackSeverity.parse(tl.severity)
    }

    private RateMonitor createRateMonitor() {
        new RateMonitor('rateMonitor', conf.maxEntriesPerMin as Long ?: 1000, 1 * MINUTES, this)
    }

    private boolean applyRateLimiting(Collection<Map> entries) {
        rateMonitor.noteRequests(entries.size())
        if (!rateLimitActive && rateMonitor.limitExceeded) {
            logError(
                'Track persistence disabled due to non-compliant load',
                [entriesPerMin: rateMonitor.maxPeriodRequests]
            )
            rateLimitActive = true
        } else if (rateLimitActive && rateMonitor.periodsInCompliance >= 2) {
            logInfo(
                'Track persistence being re-enabled after multiple periods of compliant load.',
                [compliantMins: rateMonitor.periodsInCompliance]
            )
            rateLimitActive = false
        }
    }

    void clearCaches() {
        // Do *not* clear rateLimitActive. Allow clearCaches usage in critical response situations.
        rateMonitor = createRateMonitor()
        super.clearCaches()
    }

    Map getAdminStats() {
        [
            config            : configForAdminStats('xhActivityTrackingConfig'),
            persistanceDisabled: persistenceDisabled,
            rateLimitActive: rateLimitActive,
            rateMonitor: rateMonitor
        ]
    }
}
