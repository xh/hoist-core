/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import ch.qos.logback.classic.Level
import org.slf4j.Logger

import static ch.qos.logback.classic.Level.ERROR
import static ch.qos.logback.classic.Level.WARN
import static ch.qos.logback.classic.Level.INFO
import static ch.qos.logback.classic.Level.DEBUG
import static ch.qos.logback.classic.Level.TRACE
import static io.xh.hoist.util.Utils.exceptionRenderer
import static io.xh.hoist.util.Utils.identityService
import static java.lang.System.currentTimeMillis

trait LogSupport {

    /**
     * Log at INFO level.
     *
     * If an exception is provided, basic summary info about it will be appended. If effective
     * logging level is TRACE, a stacktrace will be included as well. This aims to avoid logs being
     * spammed by recurring / lengthy stacktraces, while still providing a clear indication that an
     * error occurred plus access to stacktraces when troubleshooting an ongoing situation.
     *
     * @param msgs - one or more objects that can be converted into strings
     */
    void logInfo(Object... msgs) {logInfoInternal(instanceLog, msgs)}

    /** Log at TRACE level.*/
    void logTrace(Object... msgs) {logTraceInternal(instanceLog, msgs)}

    /** Log at DEBUG level.*/
    void logDebug(Object... msgs) {logDebugInternal(instanceLog, msgs)}

    /** Log at WARN level.*/
    void logWarn(Object... msgs) {logWarnInternal(instanceLog, msgs)}

    /** Log at ERROR level.*/
    void logError(Object... msgs) {logErrorInternal(instanceLog, msgs)}

    /**
     * Log closure execution at INFO level
     *
     * This method will run the passed closure, then log a summary message on INFO indicating the
     * elapsed time to complete and whether the closure threw or completed successfully.
     *
     * If the configured logging level is TRACE, an additional line will be written BEFORE the
     * closure is started, providing a finer-grained view on when logged routines start and end.
     *
     * @param msgs - one or more objects that can be converted into strings.
     * @param c - closure to be run and timed.
     * @return result of executing c
     */
    Object withInfo(Object msgs, Closure c)             {withInfoInternal(instanceLog, msgs, c)}

    /** Log closure execution at DEBUG level */
    Object withDebug(Object msgs, Closure c)            {withDebugInternal(instanceLog, msgs, c)}

    /** Log closure execution at TRACE level */
    Object withTrace(Object msgs, Closure c)            {withTraceInternal(instanceLog, msgs, c)}

    //-------------------------------------------------------------
    // Support logging from base class logger
    // Currently undocumented, pending better understanding of need
    //--------------------------------------------------------------
    void logInfoInBase(Object... msgs)   {logInfoInternal((Logger) log, msgs)}
    void logTraceInBase(Object... msgs)  {logTraceInternal((Logger) log, msgs)}
    void logDebugInBase(Object... msgs)  {logDebugInternal((Logger) log, msgs)}
    void logWarnInBase(Object... msgs)   {logWarnInternal((Logger) log, msgs)}
    void logErrorInBase(Object... msgs)  {logErrorInternal((Logger) log, msgs)}
    Object withInfoInBase(Object msgs, Closure c)      {withInfoInternal((Logger) log, msgs, c)}
    Object withDebugInBase(Object msgs, Closure c)     {withDebugInternal((Logger) log, msgs, c)}
    Object withTraceInBase(Object msgs, Closure c)     {withTraceInternal((Logger) log, msgs, c)}

    /**
     * Expose the conventional logger associated with the class of the concrete instance of
     * this object. This may be useful for code in super classes or auxiliary classes that want
     * to produce log statements in the loggers of particular concrete instances.
     */
    Logger getInstanceLog() {
        log
    }

    //---------------------------------------------------------------------------
    // Implementation
    //---------------------------------------------------------------------------
    private static void logInfoInternal(Logger log, Object[] msgs) {
        if (log.infoEnabled) {
            def msgCol = flatten(msgs),
                txt = delimitedTxt(msgCol),
                t = log.traceEnabled ? getThrowable(msgCol) : null
            t ? log.info(txt, t) : log.info(txt)
        }
    }

    private static void logTraceInternal(Logger log, Object[] msgs) {
        if (log.traceEnabled) {
            def msgCol = flatten(msgs),
                txt = delimitedTxt(msgCol),
                t = getThrowable(msgCol)
            t ? log.trace(txt, t) : log.trace(txt)
        }
    }

    private static void logDebugInternal(Logger log, Object[] msgs) {
        if (log.debugEnabled) {
            def msgCol = flatten(msgs),
                txt = delimitedTxt(msgCol),
                t = log.traceEnabled ? getThrowable(msgCol) : null
            t ? log.debug(txt, t) : log.debug(txt)
        }
    }

    private static void logWarnInternal(Logger log, Object[] msgs) {
        if (log.warnEnabled) {
            def msgCol = flatten(msgs),
                txt = delimitedTxt(msgCol),
                t = log.traceEnabled ? getThrowable(msgCol) : null
            t ? log.warn(txt, t) : log.warn(txt)
        }
    }

    private static void logErrorInternal(Logger log, Object[] msgs) {
        if (log.errorEnabled) {
            def msgCol = flatten(msgs),
                txt = delimitedTxt(msgCol),
                t = log.traceEnabled ? getThrowable(msgCol) : null
            t ? log.error(txt, t) : log.error(txt)
        }
    }

    private static Object withInfoInternal(Logger log, Object msgs, Closure c) {
        log.infoEnabled ? loggedDo(log, INFO, msgs, c) : c.call()
    }

    private static Object withDebugInternal(Logger log, Object msgs, Closure c) {
        log.debugEnabled ? loggedDo(log, DEBUG,  msgs, c) : c.call()
    }

    private static Object withTraceInternal(Logger log, Object msgs, Closure c) {
        log.debugEnabled ? loggedDo(log, DEBUG,  msgs, c) : c.call()
    }

    private static Object loggedDo(Logger log, Level level, Object msgs, Closure c) {
        def start = currentTimeMillis(),
            msgCol =  msgs instanceof List ? msgs.flatten() : [msgs],
            txt = delimitedTxt(msgCol),
            ret

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

    static private String getThrowable(List msgs) {
        def last = msgs.last()
        return last instanceof Throwable ? last : null
    }

    static private String delimitedTxt(List msgs) {
        def username = identityService?.username
        List<String> ret = msgs.collect {
            it instanceof Throwable ? exceptionRenderer.summaryTextForThrowable(it) : it.toString()
        }
        if (username) ret.push(username)
        return ret.join(' | ')
    }

    static private List flatten(Object[] msgs) {
       Arrays.asList(msgs).flatten()
    }
}
