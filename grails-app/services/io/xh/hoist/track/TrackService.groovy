/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import grails.events.EventPublisher
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService

import static io.xh.hoist.browser.Utils.getBrowser
import static io.xh.hoist.browser.Utils.getDevice
import static io.xh.hoist.json.JSONSerializer.serialize
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static grails.async.Promises.task
import static io.xh.hoist.util.Utils.getCurrentRequest

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
 * disabling it completely. Separately, the `disableTrackLog` *instance* config can be used to
 * disable only the *persistence* of new track logs while leaving logging and the admin client UI
 * active / accessible (intended for local development environments).
 */
@CompileStatic
class TrackService extends BaseService implements EventPublisher {

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
     *   @param args
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
     *      severity {String}           - optional, defaults to 'INFO'.
     *      elapsed {int}               - optional, time associated with action in millis
     */
    void track(Map args) {
        try {
            createTrackLog(args)
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
    private void createTrackLog(Map params) {
        if (!enabled) {
            logTrace("Activity tracking disabled via config", "track log with message [${params.msg}] will not be persisted")
            return
        }

        String userAgent = currentRequest?.getHeader('User-Agent')
        String data = params.data ? serialize(params.data) : null

        if (data?.size() > (conf.maxDataLength as Integer)) {
            logTrace("Track log with message [${params.msg}] includes ${data.size()} chars of JSON data", "exceeds limit of ${conf.maxDataLength}", "data will not be persisted")
            data = null
        }

        Map values = [
            username: params.username ?: authUsername,
            impersonating: params.impersonating ?: (identityService.isImpersonating() ? username : null),
            category: params.category ?: 'Default',
            correlationId: params.correlationId,
            msg: params.msg,
            userAgent: userAgent,
            browser: getBrowser(userAgent),
            device: getDevice(userAgent),
            elapsed: params.elapsed,
            severity: params.severity ?: 'INFO',
            data: data
        ]

        // Execute asynchronously after we get info from request, don't block application thread.
        // Save with additional try/catch to alert on persistence failures within this async block.
        task {
            TrackLog.withTransaction {

                // 1) Save in DB
                TrackLog tl = new TrackLog(values)
                if (getInstanceConfig('disableTrackLog') != 'true') {
                    try {
                        tl.save()
                    } catch (Exception e) {
                        logError('Exception writing track log', e)
                    }
                }

                // 2) Logging
                // 2a) Log core info,
                String name = tl.username
                if (tl.impersonating) name += " (as ${tl.impersonating})"
                Map<String, Object> msgParts = [
                    _user     : name,
                    _category : tl.category,
                    _msg      : tl.msg,
                    _elapsedMs: tl.elapsed
                ].findAll { it.value != null } as Map<String, Object>

                // 2b) Log app data, if requested/configured.
                if (data && (params.data instanceof Map)) {

                    def logData = params.logData != null
                        ? params.logData
                        : conf.logData != null
                        ? conf.logData
                        : false

                    if (logData) {
                        Map<String, Object> dataParts = params.data as Map<String, Object>
                        dataParts = dataParts.findAll { k, v ->
                            (logData === true || (logData as List).contains(k)) &&
                                !(v instanceof Map || v instanceof List)
                        }
                        msgParts.putAll(dataParts)
                    }
                }

                logInfo(msgParts)
            }
        }
    }
}
