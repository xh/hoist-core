/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track


import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.xh.hoist.BaseService
import io.xh.hoist.telemetry.metric.MetricsService

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Service to record Micrometer metrics based on client activity track log entries.
 *
 * Subscribes to the {@code xhTrackReceived} Hazelcast topic on the primary instance
 * to count all track messages, client errors, and record app load timings.
 *
 * All metrics are tagged with the client app name ({@code clientApp}) to support
 * multi-app deployments.
 *
 * @internal - not intended for direct use by applications.
 */
class TrackMetricsService extends BaseService {

    String telemetryPrefix = 'xh.client'

    MetricsService metricsService

    private final Map<String, AppMeters> metersByApp = new ConcurrentHashMap<>()

    void init() {
        // Description, default tags, and distribution config are per-name — applied via the
        // meter filter pipeline to all tagged variants registered later by AppMeters.
        def defaultTags = ['xh.instance': 'cluster']
        metricsService.configureCounter(
            name: 'track.messages',
            description: 'Track log entries received',
            tags: defaultTags,
            owner: this
        )
        metricsService.configureCounter(
            name: 'track.errors',
            description: 'Client error track entries',
            tags: defaultTags,
            owner: this
        )
        metricsService.configureTimer(
            name: 'load.totalTime',
            description: 'Total app load elapsed time',
            tags: defaultTags,
            publishHistogram: true,
            owner: this
        )
        metricsService.configureTimer(
            name: 'load.authTime',
            description: 'App load authentication phase duration',
            tags: defaultTags,
            publishHistogram: true,
            owner: this
        )

        subscribeToTopic(
            topic: 'xhTrackReceived',
            onMessage: this.&onTrackReceived,
            primaryOnly: true
        )
    }


    //------------------------
    // Implementation
    //------------------------
    private void onTrackReceived(TrackLog tl) {
        def meters = metersForApp(tl.clientAppCode ?: 'unknown')

        meters.messages.increment()

        if (tl.category == 'Client Error') {
            meters.errors.increment()
        }

        if (tl.category == 'App' && tl.msg?.startsWith('Loaded')) {
            def timings = tl.dataAsObject?.timings as Map
            if (timings && tl.elapsed) {
                meters.totalTime.record(tl.elapsed, TimeUnit.MILLISECONDS)
                def authMs = timings.authenticating
                if (authMs instanceof Number) {
                    meters.authTime.record(authMs.longValue(), TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private AppMeters metersForApp(String app) {
        metersByApp.computeIfAbsent(app) { new AppMeters(app, this) }
    }

    private static class AppMeters {
        final Counter messages
        final Counter errors
        final Timer totalTime
        final Timer authTime

        AppMeters(String app, TrackMetricsService svc) {
            def tags = [clientApp: app],
                ms = svc.metricsService

            messages = ms.registerCounter(name: 'track.messages', tags: tags, owner: svc)
            errors = ms.registerCounter(name: 'track.errors', tags: tags, owner: svc)
            totalTime = ms.registerTimer(name: 'load.totalTime', tags: tags, owner: svc)
            authTime = ms.registerTimer(name: 'load.authTime', tags: tags, owner: svc)
        }
    }


    void clearCaches() {
        metersByApp.clear()
        super.clearCaches()
    }

    Map getAdminStats() {
        metersByApp.collectEntries { app, meters -> [
            (app): [
                messages : meters.messages.count(),
                errors   : meters.errors.count(),
                loads    : meters.totalTime.count(),
                authLoads: meters.authTime.count()
            ]
        ]}
    }

}
