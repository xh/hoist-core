/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import grails.events.annotation.Subscriber
import org.grails.datastore.mapping.engine.event.PostDeleteEvent
import org.grails.datastore.mapping.engine.event.PostInsertEvent
import org.grails.datastore.mapping.engine.event.PostUpdateEvent
import io.xh.hoist.BaseService
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.Level
import org.slf4j.LoggerFactory

class LogLevelService extends BaseService {

    private static List<LogLevelAdjustment> adjustments = []

    void init() {
        def overrides = LogLevel.findAllByLevelIsNotNull()

        // Remove obsolete log settings if any
        adjustments.each {adj ->
            def override = overrides.find {it.name == adj.logName}
            if (!override) {
                setLogLevel(adj.logName, adj.defaultLevel)
            }
        }

        // Calculate new adjustments
        def newAdjustments = overrides.collect {override ->
             new LogLevelAdjustment(
                    logName: override.name,
                    defaultLevel: getDefaultLevel(override.name),
                    adjustedLevel: override.level
            )
        }

        // Overlay new log settings
        newAdjustments.each {adj -> setLogLevel(adj.logName, adj.adjustedLevel)}

        adjustments = newAdjustments

        log.debug("LogLevel Adjustments applied: ${adjustments.size()}")

        super.init()
    }

    void noteLogLevelsChanged() {
        LogLevel.withNewSession {
            init()
        }
    }

    // Returns the default log level (the level originally set in grails config file)
    // This is stored in the adjustments list; if no adjustment then it is the current level of the logger
    String getDefaultLevel(String logName) {
        def adjustment =  adjustments.find {it.logName == logName}
        if (adjustment) return adjustment.defaultLevel
        def logger = getLogger(logName)
        return logger.level ? logger.level.toString().toLowerCase().capitalize() : 'Inherit'
    }

    // Returns the effective level (the level the logger is currently logging at)
    String getEffectiveLevel(String logName) {
        def logger = getLogger(logName)
        return logger.effectiveLevel.toString().toLowerCase().capitalize()
    }

    
    //------------------------
    // Implementation
    //------------------------
    private Logger getLogger(String logName) {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        if (logName == 'root') logName = Logger.ROOT_LOGGER_NAME
        return lc.getLogger(logName)
    }

    private void setLogLevel(String logName, String levelStr) {
        def logger = getLogger(logName)
        logger.level = ('Inherit' == levelStr) ? null : Level[levelStr.toUpperCase()]
    }

    private class LogLevelAdjustment {
        String logName
        String defaultLevel
        String adjustedLevel
    }

    @Subscriber
    void afterDelete(PostDeleteEvent event) {
        if (!(event.entityObject instanceof LogLevel)) return
        noteLogLevelsChanged()
    }
    @Subscriber
    void afterUpdate(PostUpdateEvent event) {
        if (!(event.entityObject instanceof LogLevel)) return
        noteLogLevelsChanged()
    }
    @Subscriber
    void afterInsert(PostInsertEvent event) {
        if (!(event.entityObject instanceof LogLevel)) return
        noteLogLevelsChanged()
    }
    
}
