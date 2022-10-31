/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
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
    <T> T withInfo(Object msgs, Closure<T> c)   {withInfoInternal(instanceLog, msgs, c)}

    /** Log closure execution at DEBUG level */
    <T> T withDebug(Object msgs, Closure<T> c)  {withDebugInternal(instanceLog, msgs, c)}

    /** Log closure execution at TRACE level */
    <T> T withTrace(Object msgs, Closure<T> c)  {withTraceInternal(instanceLog, msgs, c)}

    //-------------------------------------------------------------
    // Support logging from base class logger
    // Currently undocumented, pending better understanding of need
    //--------------------------------------------------------------
    void logInfoInBase(Object... msgs)   {logInfoInternal((Logger) log, msgs)}
    void logTraceInBase(Object... msgs)  {logTraceInternal((Logger) log, msgs)}
    void logDebugInBase(Object... msgs)  {logDebugInternal((Logger) log, msgs)}
    void logWarnInBase(Object... msgs)   {logWarnInternal((Logger) log, msgs)}
    void logErrorInBase(Object... msgs)  {logErrorInternal((Logger) log, msgs)}
    <T> T withInfoInBase(Object msgs, Closure<T> c)     {withInfoInternal((Logger) log, msgs, c)}
    <T> T withDebugInBase(Object msgs, Closure<T> c)    {withDebugInternal((Logger) log, msgs, c)}
    <T> T withTraceInBase(Object msgs, Closure<T> c)    {withTraceInternal((Logger) log, msgs, c)}

    /**
     * Expose the logger used by the main instance methods on this class.
     * The default implementation of this is the SLFJ `log` property on the concrete
     * instance (subclass).
     *
     * May be overridden to apply logging to a different class.
     */
    Logger getInstanceLog() {
        log
    }

    //---------------------------------------------------------------------------
    // Implementation
    //---------------------------------------------------------------------------
    private void logInfoInternal(Logger log, Object[] msgs) {
        if (log.infoEnabled) {
            def msgCol = flatten(msgs),
                txt = delimitedTxt(msgCol),
                t = log.traceEnabled ? getThrowable(msgCol) : null
            t ? log.info(txt, t) : log.info(txt)
        }
    }

    private void logTraceInternal(Logger log, Object[] msgs) {
        if (log.traceEnabled) {
            def msgCol = flatten(msgs),
                txt = delimitedTxt(msgCol),
                t = getThrowable(msgCol)
            t ? log.trace(txt, t) : log.trace(txt)
        }
    }

    private void logDebugInternal(Logger log, Object[] msgs) {
        if (log.debugEnabled) {
            def msgCol = flatten(msgs),
                txt = delimitedTxt(msgCol),
                t = log.traceEnabled ? getThrowable(msgCol) : null
            t ? log.debug(txt, t) : log.debug(txt)
        }
    }

    private void logWarnInternal(Logger log, Object[] msgs) {
        if (log.warnEnabled) {
            def msgCol = flatten(msgs),
                txt = delimitedTxt(msgCol),
                t = log.traceEnabled ? getThrowable(msgCol) : null
            t ? log.warn(txt, t) : log.warn(txt)
        }
    }

    private void logErrorInternal(Logger log, Object[] msgs) {
        if (log.errorEnabled) {
            def msgCol = flatten(msgs),
                txt = delimitedTxt(msgCol),
                t = log.traceEnabled ? getThrowable(msgCol) : null
            t ? log.error(txt, t) : log.error(txt)
        }
    }

    private <T> T withInfoInternal(Logger log, Object msgs, Closure<T> c) {
        log.infoEnabled ? loggedDo(log, INFO, msgs, c) : c.call()
    }

    private <T> T withDebugInternal(Logger log, Object msgs, Closure<T> c) {
        log.debugEnabled ? loggedDo(log, DEBUG,  msgs, c) : c.call()
    }

    private <T> T withTraceInternal(Logger log, Object msgs, Closure<T> c) {
        log.traceEnabled ? loggedDo(log, TRACE,  msgs, c) : c.call()
    }

    private <T> T loggedDo(Logger log, Level level, Object msgs, Closure<T> c) {
        def start = currentTimeMillis(),
            msgCol =  msgs instanceof List ? msgs.flatten() : [msgs],
            txt = delimitedTxt(msgCol),
            ret

        if (log.debugEnabled) {
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

    private void logAtLevel(Logger log, Level level, GString msg) {
        switch (level) {
            case DEBUG: log.debug (msg); break
            case INFO:  log.info (msg); break
            case WARN:  log.warn (msg); break
            case ERROR: log.error (msg); break
            case TRACE: log.trace(msg); break
        }
    }

    private Throwable getThrowable(List msgs) {
        def last = msgs.last()
        return last instanceof Throwable ? last : null
    }

    private String delimitedTxt(List msgs) {
        def username = identityService?.username
        List<String> ret = msgs.collect {
            it instanceof Throwable ? exceptionRenderer.summaryTextForThrowable(it) : it.toString()
        }
        if (username) ret.add(username)
        return ret.join(' | ')
    }

    private List flatten(Object[] msgs) {
       Arrays.asList(msgs).flatten()
    }
}
