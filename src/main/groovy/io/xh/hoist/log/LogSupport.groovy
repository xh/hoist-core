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
import static ch.qos.logback.classic.Level.ERROR
import static ch.qos.logback.classic.Level.WARN
import static ch.qos.logback.classic.Level.INFO
import static ch.qos.logback.classic.Level.DEBUG
import static ch.qos.logback.classic.Level.TRACE
import static io.xh.hoist.util.Utils.exceptionRenderer
import static io.xh.hoist.util.Utils.identityService
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
    Object withInfo(Object msgs, Closure c)         {withInfo((Logger) log, msgs, c)}

    /**
     * Run a closure with a managed log message at Debug level.
     * see withInfo() for more information.
     */
    Object withDebug(Object msgs, Closure c)        {withDebug((Logger) log, msgs, c)}

    /**
     * Log an exception at Error level.
     *
     * Basic summary information about the exception will be appended.
     * If logging level is TRACE, a stacktrace will be included as well
     */
    void logErrorCompact(Object msg, Throwable e) {logErrorCompact((Logger) log, msg, e)}


    /**
     * Log an exception at Debug level.
     *
     * Basic summary information about the exception will be appended.
     * If logging level is TRACE, a stacktrace will be included as well.
     */
    void logDebugCompact(Object msg, Throwable e) {logDebugCompact((Logger) log, msg, e)}


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
    static Object withInfo(Logger log, Object msgs, Closure c) {
        log.infoEnabled ? loggedDo(log, INFO, msgs, c) : c.call()
    }

    /**
     *  Run a closure with a managed log message.
     *
     *  This is a static variant of the instance method with the same name.
     *  This is typically used by base classes, that wish to use it with
     *  getInstanceLog().
     *
     *  Applications should typically use the instance method instead.
     */
    static Object withDebug(Logger log, Object msgs, Closure c) {
        log.debugEnabled ? loggedDo(log, DEBUG,  msgs, c) : c.call()
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
    static logErrorCompact(Logger log, Object msg, Throwable t) {
        if (!log.errorEnabled) return

        String summary = exceptionRenderer.summaryTextForThrowable(t)
        String txt = delimitedTxtWithUser([msg, summary])

        if (log.traceEnabled) {
            log.error(txt, t)
        } else {
            log.error(txt)
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
    static logDebugCompact(Logger log, Object msg, Throwable t) {
        if (!log.debugEnabled) return

        String summary = exceptionRenderer.summaryTextForThrowable(t)
        String txt = delimitedTxtWithUser([msg, summary])

        if (log.traceEnabled) {
            log.debug(txt, t)
        } else {
            log.debug(txt)
        }
    }

    //------------------------
    // Implementation
    //------------------------
    static private Object loggedDo(Logger log, Level level, Object msgs, Closure c) {
        long start = currentTimeMillis()
        String txt = delimitedTxtWithUser(msgs instanceof Collection ? msgs.toList() : [msgs])
        def ret

        if (log.traceEnabled) {
            logAtLevel(log, level, "$txt | started")
        }

        try {
            ret = c.call()
        } catch (Exception e) {
            long elapsed = currentTimeMillis() - start
            logAtLevel(log, level, "$txt | failed | ${elapsed}ms")
            throw e
        }

        long elapsed = currentTimeMillis() - start
        logAtLevel(log, level, "$txt | completed | ${elapsed}ms")

        return ret
    }

    static private void logAtLevel(Logger log, Level level, GString msg) {
        switch (level) {
            case DEBUG: log.debug (msg); break
            case INFO:  log.info (msg); break
            case WARN:  log.warn (msg); break
            case ERROR: log.error (msg); break
            case TRACE: log.trace(msg); break
        }
    }

    static private delimitedTxtWithUser(List<Object> msgs) {
        msgs.push(identityService.user?.username)
        return msgs.findAll().collect { it.toString() }.join(' | ')
    }
}
