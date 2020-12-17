/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import ch.qos.logback.classic.Level
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.slf4j.Logger

import static ch.qos.logback.classic.Level.DEBUG
import static ch.qos.logback.classic.Level.INFO
import static ch.qos.logback.classic.Level.WARN
import static ch.qos.logback.classic.Level.ERROR
import static io.xh.hoist.util.Utils.exceptionRenderer
import static java.lang.System.currentTimeMillis


@Slf4j
@CompileStatic
trait LogSupport {

    /**
     * Expose the conventional logger associated with the class of the concrete instance of
     * this object. This is useful for code in super classes or auxiliary classes that want
     * to produce log statements in the loggers of particular concrete instances.
     */
    @CompileDynamic
    Logger getInstanceLog() {
        log
    }

    //------------------------------------------------------------------------------------
    // Main variants.  Will use closest inherited logger starting from the *class*
    // where the logging statement is written.
    // -----------------------------------------------------------------------------------
    /**
     * Run a closure with a managed log message.
     *
     * This method will run the passed closure, with a summary logging message
     * indicating the time to complete, and whether the closure threw an exception
     * or completed successfully.  The log message will be written as 'INFO'.
     *
     * If the configured logging level is TRACE, an additional line will be written
     * BEFORE the closure  is started, for troubleshooting purposes.
     *
     * @param msgs - one or more objects that can be converted into strings.
     *      Will be joined with a '|' delimiter.
     * @param c - closure to be run.
     * @return result of executing c
     */
    Object withInfo(Object msgs, Closure c)         {withInfo(log, msgs, c)}

    /**
     * Run a closure with a managed log message on DEBUG.
     * see withInfo() for more information.
     */
    Object withDebug(Object msgs, Closure c)        {withDebug(log, msgs, c)}

    /**
     * Log an exception at Error level.
     *
     * Basic summary information about the exception will be appended.
     * If logging level is TRACE, a stacktrace will be included as well
     */
    void logErrorCompact(Object msg, Throwable e) {logErrorCompact(log, msg, e)}


    /**
     * Log an exception at Debug level.
     *
     * Basic summary information about the exception will be appended.
     * If logging level is TRACE, a stacktrace will be included as well.
     */
    void logDebugCompact(Object msg, Throwable e) {logDebugCompact(log, msg, e)}


    //---------------------------------------------------------------------------
    // Variants for logging to a specific logger.
    // Typically used with getInstanceLog(), or when calling from a static method
    //---------------------------------------------------------------------------
    /**
     *  Run a closure with managed log message.
     *
     *  This is a static variant of the instance method with the same name.
     *  This is typically used by base classes, that wish to use it with
     *  getInstanceLog().
     *
     *  Applications should typically use the instance method instead.
     */
    @CompileDynamic
    static Object withInfo(Object log, Object msgs, Closure c) {
        loggedDo(log, INFO, msgs, log.traceEnabled, c)
    }

    /**
     *  Run a closure with managed log message.
     *
     *  This is a static variant of the instance method with the same name.
     *  This is typically used by base classes, that wish to use it with
     *  getInstanceLog().
     *
     *  Applications should typically use the instance method instead.
     */
    @CompileDynamic
    static Object withDebug(Object log, Object msgs, Closure c) {
        loggedDo(log, DEBUG, msgs, log.traceEnabled, c)
    }

    /**
     * Log an exception at Error level.
     *
     * This is a static variant of the instance method with the same name.
     * This is typically used by base classes, that wish to use it with
     * getInstanceLog().
     *
     * Applications should typically use the instance method instead.
     */
     @CompileDynamic
    static logErrorCompact(Object log, Object msg, Throwable t) {
        String message = exceptionRenderer.summaryTextForThrowable(t)
        if (msg) message = msg.toString() + ' | ' + message

        if (log.traceEnabled) {
            log.error(message, t)
        } else {
            log.error(message + ' [log on trace for more...]')
        }
    }

    /**
     * Log an exception at Debug level.
     *
     * This is a static variant of the instance method with the same name.
     * This is typically used by base classes, that wish to use it with
     * getInstanceLog().
     *
     * Applications should typically use the instance method instead.
     */
    @CompileDynamic
    static logDebugCompact(Object log, Object msg, Throwable t) {
        String message = exceptionRenderer.summaryTextForThrowable(t)
        if (msg) message = msg.toString() + ' | ' + message

        if (log.traceEnabled) {
            log.debug(message, t)
        } else {
            log.debug(message + ' [log on trace for more...]')
        }
    }

    //------------------------
    // Implementation
    //------------------------
    static private Object loggedDo(Object log, Level level, Object msgs, boolean full, Closure c) {
        long start = currentTimeMillis()
        String msg = msgs instanceof Collection ? msgs.collect {it.toString()}.join(' | ') : msgs.toString()
        def ret

        if (full) logAtLevel(log, level, "$msg | started")

        try {
            ret = c.call()
        } catch (Exception e) {
            long elapsed = currentTimeMillis() - start
            def exceptionSummary = exceptionRenderer.summaryTextForThrowable((Throwable) e)
            logAtLevel(log, level, "$msg | failed - $exceptionSummary | ${elapsed}ms")
            throw e
        }

        long elapsed = currentTimeMillis() - start
        logAtLevel(log, level, "$msg | completed | ${elapsed}ms")

        return ret
    }

    @CompileDynamic
    static private void logAtLevel(Object log, Level level, GString msg) {
        switch (level) {
            case DEBUG: log.debug (msg); break
            case INFO:  log.info (msg); break
            case WARN:  log.warn (msg); break
            case ERROR: log.error (msg); break
        }
    }
}
