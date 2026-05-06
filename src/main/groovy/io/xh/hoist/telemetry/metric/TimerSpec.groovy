/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.metric

import groovy.transform.CompileStatic

import java.time.Duration

/**
 * Internal storage type for {@link MetricsService#configureTimer} - holds the configuration
 * captured at init time so it can be applied via a {@link io.micrometer.core.instrument.config.MeterFilter}
 * and surfaced in the admin metrics view.
 *
 * @internal
 */
@CompileStatic
class TimerSpec {
    String name
    String description
    List<Double> percentiles
    List<Duration> slos
    boolean publishHistogram
    Duration minExpected
    Duration maxExpected
}
