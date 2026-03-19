/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.log

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A concrete instance of Log Support that allows logging to an arbitrary logger.
 */
@CompileStatic
class SimpleLogger implements LogSupport {

    String loggerName

    SimpleLogger(String loggerName) {
        this.loggerName = loggerName
    }

    Logger getInstanceLog() {
        LoggerFactory.getLogger(loggerName)
    }
}
