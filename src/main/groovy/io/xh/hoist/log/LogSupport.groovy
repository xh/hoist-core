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
import static java.lang.System.currentTimeMillis

trait LogSupport {

    static USE_LOG_SUPPORT = 'USE_XH_LOG_SUPPORT'
    static USE_LOG_SUPPORT_WITH_STACKTRACE = 'USE_XH_LOG_SUPPORT_WITH_STACKTRACE'

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
            log.info(getLsFlag(log), flatten(msgs))
        }
    }

    private void logTraceInternal(Logger log, Object[] msgs) {
        if (log.traceEnabled) {
            log.trace(getLsFlag(log), flatten(msgs))
        }
    }

    private void logDebugInternal(Logger log, Object[] msgs) {
        if (log.debugEnabled) {
            log.debug(getLsFlag(log), flatten(msgs))
        }
    }

    private void logWarnInternal(Logger log, Object[] msgs) {
        if (log.warnEnabled) {
            log.warn(getLsFlag(log), flatten(msgs))
        }
    }

    private void logErrorInternal(Logger log, Object[] msgs) {
        if (log.errorEnabled) {
            log.warn(getLsFlag(log), flatten(msgs))
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

        msgs = flatten(msgs)

        if (log.debugEnabled) {
            def startMsgs = msgs + [status: "started"]
            logAtLevel(log, level, startMsgs)
        }

        def ret
        def start = currentTimeMillis()
        try {
            ret = c.call()
        } catch (Exception e) {
            long elapsed = currentTimeMillis() - start
            msgs << [status: "failed", elapsedMs: elapsed]
            logAtLevel(log, level, msgs)
            throw e
        }

        long elapsed = currentTimeMillis() - start
        msgs << [status: "completed", elapsedMs: elapsed]
        logAtLevel(log, level, msgs)

        return ret
    }

    private void logAtLevel(Logger log, Level level, Object msgs) {
        switch (level) {
            case DEBUG: log.debug (USE_LOG_SUPPORT, msgs); break
            case INFO:  log.info (USE_LOG_SUPPORT, msgs); break
            case WARN:  log.warn (USE_LOG_SUPPORT, msgs); break
            case ERROR: log.error (USE_LOG_SUPPORT, msgs); break
            case TRACE: log.trace(USE_LOG_SUPPORT, msgs); break
        }
    }

    private List flatten(Object[] msgs) {
       Arrays.asList(msgs).flatten()
    }

    private String getLsFlag(Logger log) {
        log.traceEnabled ? USE_LOG_SUPPORT_WITH_STACKTRACE : USE_LOG_SUPPORT_WITH_STACKTRACE
    }
}
