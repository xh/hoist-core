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
 * Mirrors the seedable fields of {@link Monitor} — if a new seedable field is added to the
 * domain class, it should be added here as well.
 *
 * Provides IDE autocomplete and compile-time validation for monitor definitions.
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
