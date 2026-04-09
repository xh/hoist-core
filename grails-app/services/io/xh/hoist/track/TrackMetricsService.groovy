/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import groovy.transform.CompileStatic
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.xh.hoist.BaseService
import io.xh.hoist.telemetry.MetricsService

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

    MetricsService metricsService

    private final Map<String, AppMeters> metersByApp = new ConcurrentHashMap<>()

    void init() {
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
            def tags = Tags.of('xh.source', 'hoist', 'instance', 'cluster', 'clientApp', app),
                registry = svc.metricsService.registry

            messages = Counter.builder('hoist.client.track.messages')
                .description('Track log entries received')
                .tags(tags)
                .register(registry)

            errors = Counter.builder('hoist.client.track.errors')
                .description('Client error track entries')
                .tags(tags)
                .register(registry)

            totalTime = Timer.builder('hoist.client.load.totalTime')
                .description('Total app load elapsed time')
                .tags(tags)
                .register(registry)

            authTime = Timer.builder('hoist.client.load.authTime')
                .description('App load authentication phase duration')
                .tags(tags)
                .register(registry)
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
