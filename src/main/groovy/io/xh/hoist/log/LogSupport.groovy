/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
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
     * Log at INFO level.
     *
     * If an exception is provided, basic summary info about it will be appended.
     * If logging level is TRACE, a stacktrace will be included as well.
     *
     * @param msgs - one or more objects that can be converted into strings
     * @param t - Throwable to be logged, optional
     */
    void logInfo(Object msgs, Throwable t = null) {logInfo((Logger) log, msgs, t)}

    /** Log at TRACE level.*/
    void logTrace(Object msgs, Throwable t = null) {logTrace((Logger) log, msgs, t)}

    /** Log at DEBUG level.*/
    void logDebug(Object msgs, Throwable t = null) {logDebug((Logger) log, msgs, t)}

    /** Log at WARN level.*/
    void logWarn(Object msgs, Throwable t = null) {logWarn((Logger) log, msgs, t)}

    /** Log at ERROR level.*/
    void logError(Object msgs, Throwable t = null) {logError((Logger) log, msgs, t)}


    /**
     * Log closure execution at INFO level
     *
     * This method will run the passed closure, with a summary logging message
     * indicating the time to complete, and whether the closure threw an exception
     * or completed successfully.  The log message will be written as 'INFO'.
     *
     * If the configured logging level is TRACE, an additional line will be written
     * BEFORE the closure  is started, for troubleshooting purposes.
     *
     * @param msgs - one or more objects that can be converted into strings.
     * @param c - closure to be run.
     * @return result of executing c
     */
    Object withInfo(Object msgs, Closure c)         {withInfo((Logger) log, msgs, c)}

    /** Log closure execution at DEBUG level*/
    Object withDebug(Object msgs, Closure c)        {withDebug((Logger) log, msgs, c)}


    //---------------------------------------------------------------------------
    // Variants for logging to a specific logger.
    // Typically used with getInstanceLog(), or when calling from a static method
    //---------------------------------------------------------------------------
    /**
     * Log at INFO level.
     *
     * This is a static variant of the instance method with the same name.
     * This is typically used by base classes, that wish to use it with
     * getInstanceLog().
     *
     * Applications should typically use the instance method instead.
     */
    static void logInfo(Logger log, Object msgs, Throwable t = null) {
        if (log.infoEnabled) {
            String txt = delimitedTxt(msgs, t)
            log.traceEnabled && t ? log.info(txt, t) : log.info(txt)
        }
    }

    /** Log at TRACE level. */
    static void logTrace(Logger log, Object msgs, Throwable t = null) {
        if (log.traceEnabled) {
            String txt = delimitedTxt(msgs, t)
            t ? log.trace(txt, t) : log.trace(txt)
        }
    }

    /** Log at DEBUG level. */
    static void logDebug(Logger log, Object msgs, Throwable t = null) {
        if (log.debugEnabled) {
            String txt = delimitedTxt(msgs, t)
            log.traceEnabled && t ? log.debug(txt, t) : log.debug(txt)
        }
    }

    /** Log at WARN level. */
    static void logWarn(Logger log, Object msgs, Throwable t = null) {
        if (log.warnEnabled) {
            String txt = delimitedTxt(msgs, t)
            log.traceEnabled && t ? log.warn(txt, t) : log.warn(txt)
        }
    }

    /** Log at ERROR level. */
    static void logError(Logger log, Object msgs, Throwable t = null) {
        if (log.errorEnabled) {
            String txt = delimitedTxt(msgs, t)
            log.traceEnabled && t ? log.error(txt, t) : log.error(txt)
        }
    }

    /**
     *  Log closure execution at INFO level
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

    /** Log closure execution at DEBUG level. */
    static Object withDebug(Logger log, Object msgs, Closure c) {
        log.debugEnabled ? loggedDo(log, DEBUG,  msgs, c) : c.call()
    }

    //------------------------
    // Implementation
    //------------------------
    static private Object loggedDo(Logger log, Level level, Object msgs, Closure c) {
        long start = currentTimeMillis()
        String txt = delimitedTxt(msgs, null)
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

    static private String delimitedTxt(Object msgs, Throwable t) {
        def username = identityService?.username
        List<String> ret = msgs ?
                (msgs instanceof Collection ? msgs.collect { it.toString() } : [msgs.toString()]) :
                []

        if (t) ret.push(exceptionRenderer.summaryTextForThrowable(t))
        if (username) ret.push(username)
        return ret.join(' | ')
    }
}
