/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test.fakes

import groovy.transform.CompileStatic
import io.xh.hoist.telemetry.metric.MetricsService

/**
 * {@link MetricsService} for tests. The `registry` has no backing registries, so
 * `recordTimer()`, `recordCount()` and `observe()` timers and counters run without publishing
 * anything, and meters from `register*()` hold no values. `xhMetricsConfig` is never read.
 * Registered by {@link io.xh.hoist.test.HoistUnitTest} as the application's `metricsService` bean.
 */
@CompileStatic
class TestMetricsService extends MetricsService {

    // The overrides below skip MetricsService's config sync, which would read xhMetricsConfig.
    // Note clearCaches() cannot call super - the parent's implementation runs that same sync.
    @Override
    void init() {}

    @Override
    void clearCaches() {}
}
