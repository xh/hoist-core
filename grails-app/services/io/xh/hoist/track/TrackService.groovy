/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import grails.events.EventPublisher
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.json.JSON
import org.grails.web.util.WebUtils
import grails.util.Environment

import static io.xh.hoist.browser.Utils.getBrowser
import static io.xh.hoist.browser.Utils.getDevice

/**
 * Service for tracking user activity within the application. This service provides a server-side
 * API for adding track log entries, while the client-side toolkits provide corresponding APIs
 * in Javascript. Track log entries are stored within the xh_track_log database table and are
 * viewable via the Hoist Admin Console 's Client Activity > Activity grid.
 *
 * The choice of which activities to track is up to application developers. Typical use-cases
 * involve logging queries and tracking if / how often a given feature is actually used.
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

    /**
     * Return a map of Ymd value date to visit count, where *any* track logging for a given user on a given day
     * counts as one visit for that user
     * @param start - start date of query (inclusive)
     * @param end - end date of query (exclusive)
     * @param username - optional filter for single username
     */
    Map<String, Integer> getUniqueVisitsByDay(Date start, Date end, String username) {
        def query = """
            select cast(dateCreated AS date), count(distinct username)
            from TrackLog
            where dateCreated >= :start
            and dateCreated < :end
            ${username ? 'and username = :username' : ''}
            group by cast(dateCreated AS date)
        """

        Map params = username ?  [start: start, end: end, username: username] : [start: start, end: end]

        List<List> ret = TrackLog.executeQuery(query.toString(), params)
        return ret.collectEntries {
            Date trackDate = (Date) it[0]
            [trackDate.format('yyyyMMdd'), it[1]]
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
                username: authUsername,
                category: params.category ?: 'Default',
                XXmsg: params.msg,
                userAgent: userAgent,
                browser: getBrowser(userAgent),
                device: getDevice(userAgent),
                data: params.data ? new JSON(params.data).toString() : null,
                elapsed: params.elapsed,
                severity: params.severity ?: 'OK',
                impersonating: idSvc.isImpersonating() ? username : null
            ]

        // Execute asynchronously after we get info from request, don't block application thread.
        // Save with additional try/catch to alert on persistence failures within this async block.
        asyncTask {
            def tl = new TrackLog(values)

            if (!Environment.isDevelopmentMode()) {
                try {
                    tl.save()
                } catch (Exception e) {
                    logErrorCompact('Exception writing track log', e)
                }
            }

            def name = tl.username
            if (tl.impersonating) name += " (as ${tl.impersonating})"

            def msgParts = [name, tl.category, tl.msg, tl.elapsed]
            log.info(msgParts.findAll{it}.join(' | '))
        }
    }

}
