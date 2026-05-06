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
        def published = metricsService.publishedMetrics as Set
        metricsService.readOnlyRegistry.meters.collect { Meter meter ->
            def id = meter.id,
                name = id.name,
                tags = id.tags.collect { [key: it.key, value: it.value] },
                baseUnit = meter instanceof MicrometerTimer || meter instanceof LongTaskTimer
                    ? meter.baseTimeUnit().name().toLowerCase()
                    : id.baseUnit
            def row = [
                id: "$name|${tags.collect { "${it.key}=${it.value}" }.join('|')}",
                name: name,
                type: id.type.name(),
                description: id.description,
                baseUnit: baseUnit,
                tags: tags,
                published: published.contains(name)
            ]
            try {
                evaluateMeter(meter, row)
            } catch (Throwable t) {
                logDebug("Failed to read meter '$name'", t)
                row.error = t.message ?: t.class.simpleName
            }
            row
        }
    }

    private void evaluateMeter(Meter meter, Map row) {
        def type = meter.id.type,
            stats = meter.measure().collectEntries { [it.statistic.name(), it.value] }

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

        row.value = value
        row.count = count
        row.max = max
        row.stats = stats
    }

}
