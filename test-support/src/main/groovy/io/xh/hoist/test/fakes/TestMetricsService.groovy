/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test.fakes

import groovy.transform.CompileStatic
import io.xh.hoist.telemetry.metric.MetricsService

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Recording {@link MetricsService} for tests. Calls to `recordTimer()` and `recordCount()` -
 * including those from the `timer()` and `counter()` options of `observe()` - are collected in
 * {@link #getTimers} and {@link #getCounts} rather than published, so a spec can assert on the
 * metrics code under test emitted:
 *
 * <pre>
 * service.refreshPrices()
 *
 * expect:
 * testMetricsService.lastCount.name == 'priceRefreshes'
 * testMetricsService.lastCount.tags['xh.outcome'] == 'success'
 * </pre>
 *
 * Each recorded timer is a Map of `name`, `valueMs` and `tags`; each count is a Map of `name`,
 * `value` and `tags`. Meters created via `registerTimer()`, `registerCounter()`,
 * `registerGauge()` or directly on `registry` are not recorded, and hold no values - the
 * registry has no backing registries in tests. `xhMetricsConfig` is never read. Registered by
 * {@link io.xh.hoist.test.HoistUnitTest} as the application's `metricsService` bean and cleared
 * before each feature.
 */
@CompileStatic
class TestMetricsService extends MetricsService {

    private final List<Map> timerEntries = new CopyOnWriteArrayList<Map>()
    private final List<Map> countEntries = new CopyOnWriteArrayList<Map>()

    /** All timer values recorded so far, oldest first. */
    List<Map> getTimers() {
        timerEntries.asImmutable()
    }

    /** The most recently recorded timer value, or null if none. */
    Map getLastTimer() {
        timerEntries ? timerEntries.last() : null
    }

    /** Timer values recorded with the given name. */
    List<Map> timers(String name) {
        timerEntries.findAll { it.name == name }
    }

    /** All counter increments recorded so far, oldest first. */
    List<Map> getCounts() {
        countEntries.asImmutable()
    }

    /** The most recently recorded counter increment, or null if none. */
    Map getLastCount() {
        countEntries ? countEntries.last() : null
    }

    /** Counter increments recorded with the given name. */
    List<Map> counts(String name) {
        countEntries.findAll { it.name == name }
    }

    /** Forget all recorded timers and counts. */
    void clear() {
        timerEntries.clear()
        countEntries.clear()
    }

    @Override
    void recordTimer(String name, double valueMs, Map<String, String> tags) {
        timerEntries << [name: name, valueMs: valueMs, tags: new LinkedHashMap(tags ?: [:])]
    }

    @Override
    void recordCount(String name, double value, Map<String, String> tags) {
        countEntries << [name: name, value: value, tags: new LinkedHashMap(tags ?: [:])]
    }

    // The overrides below skip MetricsService's config sync, which would read xhMetricsConfig.
    @Override
    void init() {}

    @Override
    void clearCaches() {}
}
