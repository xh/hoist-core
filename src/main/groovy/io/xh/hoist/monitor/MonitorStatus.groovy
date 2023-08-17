/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONFormat

@CompileStatic
enum MonitorStatus implements JSONFormat {
    INACTIVE, UNKNOWN, OK, WARN, FAIL

    Object formatForJSON() {
        return toString()
    }

    int getSeverity() {
        return ordinal()
    }

}
