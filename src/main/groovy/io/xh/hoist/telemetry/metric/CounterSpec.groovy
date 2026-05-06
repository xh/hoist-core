/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.metric

import groovy.transform.CompileStatic

/**
 * Internal storage type for {@link MetricsService#configureCounter} - holds the description
 * captured at init time so it can be surfaced in the admin metrics view.
 * @internal
 */
@CompileStatic
class CounterSpec {
    String name
    String description
}
