/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin

import io.micrometer.core.instrument.LongTaskTimer
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Timer as MicrometerTimer
import io.xh.hoist.BaseService

import static io.micrometer.core.instrument.Meter.Type.*

class MetricsAdminService extends BaseService {

    def metricsService

    List<Map> listMetrics() {
        metricsService.readOnlyRegistry.meters.collect { Meter meter ->
            def id = meter.id,
                name = id.name,
                type = id.type,
                description = id.description,
                tags = id.tags.collect { [key: it.key, value: it.value] },
                stats = meter.measure().collectEntries { [it.statistic.name(), it.value] },
                baseUnit = meter instanceof MicrometerTimer || meter instanceof LongTaskTimer
                    ? meter.baseTimeUnit().name().toLowerCase()
                    : id.baseUnit

            def value, count, max
            switch (type) {
                case GAUGE:
                    value = stats.VALUE
                    break
                case COUNTER:
                    value = stats.COUNT
                    break
                case TIMER:
                case LONG_TASK_TIMER:
                case DISTRIBUTION_SUMMARY:
                    count = stats.COUNT
                    max = stats.MAX
                    value = count ? (stats.TOTAL_TIME ?: stats.TOTAL ?: 0) / count : 0
                    break
                default:
                    value = stats.VALUE ?: stats.COUNT ?: 0
            }

            [
                id: "$name|${tags.collect { "${it.key}=${it.value}" }.join('|')}",
                name: name,
                type: type.name(),
                value: value,
                count: count,
                max: max,
                description: description,
                baseUnit: baseUnit,
                tags: tags,
                stats: stats
            ]
        }
    }
}
