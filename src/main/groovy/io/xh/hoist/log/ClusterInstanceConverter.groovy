/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import io.xh.hoist.cluster.ClusterService

class ClusterInstanceConverter extends ClassicConverter {
    String convert(ILoggingEvent event) {
        return ClusterService.instanceName
    }
}

