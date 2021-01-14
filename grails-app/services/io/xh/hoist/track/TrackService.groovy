/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import grails.gorm.transactions.ReadOnly
import grails.events.EventPublisher
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import org.grails.web.util.WebUtils

import static io.xh.hoist.browser.Utils.getBrowser
import static io.xh.hoist.browser.Utils.getDevice
import static io.xh.hoist.json.JSONSerializer.serialize
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static grails.async.Promises.task

/**
 * Service for tracking user activity within the application. This service provides a server-side
 * API for adding track log entries, while the client-side toolkits provide corresponding APIs
 * in Javascript. Track log entries are stored within the xh_track_log database table and are
 * viewable via the Hoist Admin Console's Client Activity > Activity grid.
 *
 * The choice of which activities to track is up to application developers. Typical use-cases
 * involve logging queries and tracking if / how often a given feature is actually used.
 *
 * Persistence of track logs to the database can be disabled (e.g. in a local dev environment)
 * with an instance config value of `disableTrackLog: true`.
 */
@CompileStatic
class TrackService extends BaseService implements EventPublisher {

    /**
     * Create a new track log entry. Username, browser info, and datetime will be set automatically.
     * @param msg
     */
    void track(String msg) {
        track(msg: msg)
    }

    /**
     * Create a new track log entry. Username, browser info, and datetime will be set automatically.
     * @param params [String category, String msg, Map data, Integer elapsed, String severity]
     */
    void track(Map params) {
        try {
            createTrackLog(params)
        } catch (Exception e) {
            logErrorCompact('Exception writing track log', e)
        }
    }


    //-------------------------
    // Implementation
    //-------------------------
    private void createTrackLog(Map params) {
        def request = WebUtils.retrieveGrailsWebRequest().currentRequest,
            userAgent = request?.getHeader('User-Agent'),
            idSvc = identityService,
            authUsername = idSvc.getAuthUser().username,
            values = [
                    username     : authUsername,
                    category     : params.category ?: 'Default',
                    msg          : params.msg,
                    userAgent    : userAgent,
                    browser      : getBrowser(userAgent),
                    device       : getDevice(userAgent),
                    data         : params.data ? serialize(params.data) : null,
                    elapsed      : params.elapsed,
                    severity     : params.severity ?: 'INFO',
                    impersonating: idSvc.isImpersonating() ? username : null
            ]

        // Execute asynchronously after we get info from request, don't block application thread.
        // Save with additional try/catch to alert on persistence failures within this async block.
        task {
            TrackLog.withTransaction {
                def tl = new TrackLog(values)

                if (getInstanceConfig('disableTrackLog') != 'true') {
                    try {
                        tl.save()
                    } catch (Exception e) {
                        logErrorCompact('Exception writing track log', e)
                    }
                }

                def elapsedStr = tl.elapsed != null ? tl.elapsed + 'ms' : null,
                        name = tl.username
                if (tl.impersonating) name += " (as ${tl.impersonating})"

                def msgParts = [name, tl.category, tl.msg, elapsedStr]
                log.info(msgParts.findAll().join(' | '))
            }
        }
    }
}