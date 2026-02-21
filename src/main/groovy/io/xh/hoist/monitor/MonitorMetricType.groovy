/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

/**
 * Enumeration of supported metric types for Hoist monitors.
 *
 * Title-case values produce `.name()` strings ('Floor', 'Ceil', 'None') matching
 * the values expected by the Monitor domain class.
 */
enum MonitorMetricType {
    Floor, Ceil, None
}
