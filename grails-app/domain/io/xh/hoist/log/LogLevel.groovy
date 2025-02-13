/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

import static grails.async.Promises.task

class LogLevel implements JSONFormat {

    String name
    String level
    Date lastUpdated
    String lastUpdatedBy

    String getDefaultLevel() { logLevelService.getDefaultLevel(name) }

    String getEffectiveLevel() { logLevelService.getEffectiveLevel(name) }

    public static List<String> LEVELS = ['Trace', 'Debug', 'Info', 'Warn', 'Error', 'Inherit', 'Off']

    static mapping = {
        table 'xh_log_level'
        level column: 'log_level'
        cache true
    }

    static constraints = {
        name(unique: true, nullable: false, blank: false)
        level(nullable: true, maxSize: 20, inList: LogLevel.LEVELS)
        lastUpdatedBy(nullable: true, maxSize: 50)
    }

    def afterUpdate() {
        noteLogLevelChanged()
    }

    def afterDelete() {
        noteLogLevelChanged()
    }

    def afterInsert() {
        noteLogLevelChanged()
    }

    Map formatForJSON() {
        return [
            id            : id,
            name          : name,
            level         : level,
            defaultLevel  : defaultLevel,
            effectiveLevel: effectiveLevel,
            lastUpdated   : lastUpdated,
            lastUpdatedBy : lastUpdatedBy
        ]
    }

    private getLogLevelService() { Utils.appContext.logLevelService }

    private noteLogLevelChanged() {
        // called in a new thread and with a delay to make sure the change has had the time to propagate
        task {
            Thread.sleep(500)
            logLevelService.noteLogLevelChanged()
        }
    }

}
