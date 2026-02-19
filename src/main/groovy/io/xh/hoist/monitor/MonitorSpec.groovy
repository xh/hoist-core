/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import groovy.transform.MapConstructor

/**
 * Typed specification for a required monitor to be created by
 * {@link io.xh.hoist.monitor.provided.DefaultMonitorDefinitionService#ensureRequiredMonitorsCreated}.
 *
 * Provides IDE autocomplete and compile-time validation for monitor definitions.
 * Supports construction from named parameters or from a Map via {@code @MapConstructor}.
 */
@MapConstructor
class MonitorSpec {
    String code
    String name
    MonitorMetricType metricType
    boolean active = false
    String metricUnit
    Integer warnThreshold
    Integer failThreshold
    boolean primaryOnly = false
    String params
    String notes
}
