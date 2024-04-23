/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

class Monitor implements JSONFormat {

    String code
    String name
    String metricType
    String metricUnit
    Integer warnThreshold
    Integer failThreshold
    String params
    String notes
    Integer sortOrder
    boolean active = false
    String lastUpdatedBy
    Date lastUpdated
    Boolean primaryOnly = false

    public static List<String> METRIC_TYPES = ['Floor', 'Ceil', 'None']

    static mapping = {
        table 'xh_monitor'
        cache true
    }

    static constraints = {
        code(unique:true, blank: false)
        name(unique:true, blank: false)
        metricType(maxSize: 20, inList: Monitor.METRIC_TYPES)
        metricUnit(nullable: true)
        warnThreshold(nullable: true)
        failThreshold(nullable: true)
        params(nullable: true, maxSize: 1200, validator: {Utils.isJSON(it) ?: 'default.invalid.json.message'})
        notes(nullable: true, maxSize: 1200)
        sortOrder(nullable: true)
        lastUpdatedBy(nullable: true, maxSize: 50)
        primaryOnly(nullable: true)
    }

    Map formatForJSON() {
        return [
                id: id,
                code: code,
                name: name,
                metricType: metricType,
                metricUnit: metricUnit,
                warnThreshold: warnThreshold,
                failThreshold: failThreshold,
                params: params,
                notes: notes,
                sortOrder: sortOrder,
                active: active,
                lastUpdatedBy: lastUpdatedBy,
                lastUpdated: lastUpdated,
                primaryOnly: primaryOnly
        ]
    }

}
