package io.xh.hoist.log

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A concrete instance of Log Support that allows logging to an arbitrary logger.
 */
class SimpleLogger implements LogSupport {

    String loggerName

    SimpleLogger(String loggerName) {
        this.loggerName = loggerName
    }

    Logger getInstanceLog() {
        LoggerFactory.getLogger(loggerName)
    }
}
