/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.clienterror

import com.hazelcast.map.IMap
import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.util.Utils

import static io.xh.hoist.browser.Utils.getBrowser
import static io.xh.hoist.browser.Utils.getDevice
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static io.xh.hoist.util.Utils.getCurrentRequest

import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static java.lang.System.currentTimeMillis

/**
 * This class manages client error reports, saving them in the database and
 * broadcasting via email.
 *
 * It processes any reports received in a timer in bulk.  With a 'maxErrors' config,
 * this prevents us from ever overwhelming the server due to client issues,
 * and also allows us to produce a digest form of the email.
 */
class ClientErrorService extends BaseService {

    def clientErrorEmailService,
        configService

    static clusterConfigs = [
        clientErrors: [IMap, {
            evictionConfig.size = 100
        }]
    ]

    private IMap<String, Map> errors = getIMap('clientErrors')
    private int getMaxErrors()      {configService.getMap('xhClientErrorConfig').maxErrors as int}
    private int getAlertInterval()  {configService.getMap('xhClientErrorConfig').intervalMins * MINUTES}

    void init() {
        super.init()
        createTimer(
            interval: { alertInterval },
            delay: 15 * SECONDS,
            primaryOnly: true
        )
    }

    /**
     * Create a client exception entry. Username, browser info, environment info, and datetime will be set automatically.
     * @param message - optional comments supplied by the user
     * @param error - error generated by client-side error reporting
     * @param appVersion - expected from client to ensure we record the version user's browser is actually running
     * @param url - location where error occurred
     */
    void submit(String message, String error, String appVersion, String url, boolean userAlerted, String correlationId) {
        def request = currentRequest
        if (!request) {
            throw new RuntimeException('Cannot submit a client error outside the context of an HttpRequest.')
        }

        def userAgent = request.getHeader('User-Agent')

        if (errors.size() < maxErrors) {
            def now = currentTimeMillis()
            errors[authUsername + now] = [
                    correlationId : correlationId,
                    msg           : message,
                    error         : error,
                    username      : authUsername,
                    userAgent     : userAgent,
                    browser       : getBrowser(userAgent),
                    device        : getDevice(userAgent),
                    appVersion    : appVersion ?: Utils.appVersion,
                    appEnvironment: Utils.appEnvironment,
                    url           : url?.take(500),
                    instance      : ClusterService.instanceName,
                    userAlerted   : userAlerted,
                    dateCreated   : new Date(now),
                    impersonating: identityService.impersonating ? username : null
            ]
            logDebug("Client Error received from $authUsername", "queued for processing")
        } else {
            logDebug("Client Error received from $authUsername", "maxErrors threshold exceeded - error report will be dropped")
        }
    }

    //--------------------------------------------------------
    // Implementation
    //---------------------------------------------------------
    @Transactional
    void onTimer() {
        if (!errors) return

        def maxErrors = getMaxErrors(),
            errs = errors.values().take(maxErrors),
            count = errs.size()
        errors.clear()

        if (getInstanceConfig('disableTrackLog') != 'true') {
            withDebug("Processing $count Client Errors") {
                def errors = errs.collect {
                    def ce = new ClientError(it)
                    ce.dateCreated = it.dateCreated
                    ce.save(flush: true)
                }
                getTopic('xhClientErrorReceived').publishAllAsync(errors)
                clientErrorEmailService.sendMail(errs, count == maxErrors)
            }
        }
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhClientErrorConfig'),
        pendingErrorCount: errors.size()
    ]}

}
