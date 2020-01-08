/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

class LogLevel implements JSONFormat {

    String name
    String level
    String getDefaultLevel() {logLevelService.getDefaultLevel(name)}
    String getEffectiveLevel() {logLevelService.getEffectiveLevel(name)}

    public static List<String> LEVELS = ['Trace', 'Debug', 'Info', 'Warn', 'Error', 'Inherit']

    static mapping = {
        table 'xh_log_level'
        level column: 'log_level'
        cache true
    }

    static constraints = {
        name(unique: true, nullable: false, blank: false)
        level(nullable: true, maxSize: 20, inList: LogLevel.LEVELS)
    }

    Map formatForJSON() {
        return [
                id: id,
                name: name,
                level: level,
                defaultLevel: defaultLevel,
                effectiveLevel: effectiveLevel
        ]
    }

    private getLogLevelService() { Utils.appContext.logLevelService }

}
