/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import io.xh.hoist.log.LogSupport

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import static io.xh.hoist.util.DateTimeUtils.*
import static io.xh.hoist.util.Utils.configService
import static io.xh.hoist.util.Utils.getExceptionHandler

/**
 * Core Hoist Timer object.
 *
 * This object is typically used by services that need to schedule work to maintain
 * internal state.
 */
class Timer {

     private static Long CONFIG_INTERVAL = 15 * SECONDS

    /** Optional name for this timer (for logging purposes) **/
    final String name

    /** Object using this timer (for logging purposes) **/
    final LogSupport owner

    /** Closure to run */
    final Closure runFn

    /**
     * Interval between runs. Specify as a number, closure, or string. The units for this argument
     * are defined by intervalUnits property. If value is not positive, the job will not run.
     *
     * If specified as a function, the value will be recomputed after every run. If specified as a
     * string, the value will be assumed to be a config key and will be looked up after every run.
     */
    final Object interval

    /**
     * Max time to let function run before cancelling. Specify as a number, closure, or string.
     * The units for this argument are defined by timeoutUnits property. Default is 3 mins.
     *
     * If specified as a function, the value will be re-computed after every run. If specified as a
     * string, the value will be assumed to be a config key, and will be looked up after every run.
     */
    final Object timeout

    /**
     *  Initial delay, in milliseconds. May be specified as a boolean or a number.
     *  If true the value of the delay will be the same as interval.  Default to false.
     */
    final Object delay

    /** Units for interval property.  Default is ms (1) */
    final Long intervalUnits

    /** Units for timeout property.  Default is ms (1) */
    final Long timeoutUnits

    /** Block on an immediate initial run?  Default is false. */
    final boolean runImmediatelyAndBlock

    /**  Only run job when clustered instance is the master server?  Default is false */
    final boolean masterOnly


    /** Date last run started. */
    Date getLastRunStarted() {
        _lastRunStarted
    }

    /** Date last run completed. */
    Date getLastRunCompleted() {
        _lastRunCompleted
    }

    /** Is `runFn` currently executing? */
    boolean getIsRunning() {
        _isRunning
    }

    // NOTE that even when runImmediatelyAndBlock is false, the task may be run *nearly* immediately
    // but asynchronously, as governed by delay
    private Long intervalMs
    private Long delayMs
    private Long timeoutMs
    private Long coreIntervalMs


    private Date _lastRunCompleted = null
    private Date _lastRunStarted = null
    private Map _lastRunStats = null
    private boolean _isRunning = false
    private boolean forceRun  = false
    private java.util.Timer coreTimer
    private java.util.Timer configTimer


    // Args from Grails 3.0 async promise implementation
    static ExecutorService executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>())

    /**
     * Applications should not typically use this constructor directly. Timers are typically
     * created by services using the createTimer() method supplied by io.xh.hoist.BaseService.
     */
    Timer(Map config) {
        name = config.name
        owner = config.owner
        runFn = config.runFn
        masterOnly = config.masterOnly ?: false
        runImmediatelyAndBlock = config.runImmediatelyAndBlock ?: false
        interval = parseDynamicValue(config.interval)
        timeout = parseDynamicValue(config.containsKey('timeout') ? config.timeout : 3 * MINUTES)
        delay = config.delay ?: false

        intervalUnits = config.intervalUnits ?: 1
        timeoutUnits = config.timeoutUnits ?: 1

        if ([owner, interval, runFn].contains(null)) throw new RuntimeException('Missing required arguments for Timer.')
        if (config.delayUnits) throw new RuntimeException('delayUnits has been removed from the API. Specify delay in ms.')

        intervalMs = calcIntervalMs()
        timeoutMs = calcTimeoutMs()
        delayMs = calcDelayMs()
        coreIntervalMs = calcCoreIntervalMs()

        if (runImmediatelyAndBlock) {
            doRun()
        }

        // Core Timer
        coreTimer = new java.util.Timer()
        coreTimer.schedule((this.&onCoreTimer as TimerTask), delayMs, coreIntervalMs)

        // Aux Timer for reloading dynamic intervals
        if (interval instanceof Closure || timeout instanceof Closure) {
            configTimer = new java.util.Timer()
            configTimer.schedule(
                    (this.&onConfigTimer as TimerTask), CONFIG_INTERVAL, CONFIG_INTERVAL
            )
        }
    }

    /**
     * Force a new execution as soon as possible.
     *
     * This will occur on the next scheduled heartbeat, or as soon as any in-progress executions complete.
     * Any subsequent calls to this method before this additional execution has completed will be ignored.
     */
    void forceRun() {
        forceRun = true
    }

    /**
     * Cancel this timer.
     *
     * This will prevent any additional executions of this timer.  In-progress executions will be unaffected.
     */
    void cancel() {
        coreTimer?.cancel()
        configTimer?.cancel()
    }

    /**
     * Information about this time for admin purposes.
     */
    Map getAdminStats() {
        [
            name: name,
            intervalMs : intervalMs,
            isRunning  : isRunning,
            lastStartTime: _lastRunStarted,
            lastStats  : _lastRunStats
        ].findAll {it.value != null}
    }


    //------------------------
    // Implementation
    //------------------------
    private void doRun() {
        if (masterOnly && !Utils.clusterService.isMaster) return

        _isRunning = true
        _lastRunStarted = new Date()
        Throwable throwable = null
        Future future = null
        try {
            def callable = runFn
            future = executorService.submit(callable)
            if (timeoutMs) {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                future.get()
            }
        } catch (ExecutionException e) {
            throwable = e.cause
        } catch (TimeoutException ignored) {
            future.cancel(true)  // Important for TimeoutException, attempt to shutdown unit of work.
            throwable = new TimeoutException("Operation timed out after $timeoutMs ms.")
        } catch (Throwable t) {
            throwable = t
        }

        _lastRunCompleted = new Date()
        _isRunning = false
        _lastRunStats = [
            startTime: _lastRunStarted,
            endTime: _lastRunCompleted,
            elapsedMs: _lastRunCompleted.time - _lastRunStarted.time
        ]
        if (throwable) {
            try {
                _lastRunStats.error = exceptionHandler.summaryTextForThrowable(throwable)
                exceptionHandler.handleException(exception: throwable, logTo: owner)
            } catch (Throwable ignore) {
                owner.logError('Failed to handle exception in Timer')
            }
        }
    }

    //----------------------------------------------------------
    // Config, interval Management
    //
    // We read the config info infrequently on its own timer.
    // We want to be dynamic without adding too much load.
    //-----------------------------------------------------------
    private Long calcIntervalMs() {
        if (interval == null) return null
        Long ret = (interval instanceof Closure ? (interval as Closure)() : interval) * intervalUnits;
        if (ret > 0 && ret < 500) {
            owner.logWarn('Timer cannot be set for values less than 500ms.')
            ret = 500
        }
        return ret
    }

    private Long calcTimeoutMs() {
        if (timeout == null) return null
        return (timeout instanceof Closure ? (timeout as Closure)() : timeout) * timeoutUnits
    }

    private Long calcDelayMs() {
        if (runImmediatelyAndBlock || delay == null || delay == false) return 0
        if (delay == true) return intervalMs
        return (Long) delay
    }

    private void onConfigTimer() {
        try {
            intervalMs = calcIntervalMs()
            timeoutMs = calcTimeoutMs()
            adjustCoreTimerIfNeeded()
        } catch (Throwable t) {
            owner.logError('Timer failed to reload config', t)
        }
    }

    Object parseDynamicValue(Object obj) {
        return obj instanceof String ? {configService.getInt(obj as String)} : obj
    }

    //-------------------------------------------------------------------------------------------
    // Core Timer Management
    //
    // The lightweight timer spinning relatively fast to pickup both timeouts and forced runs.
    //
    // The vast majority of Timers have relatively long intervals -- just spin the core interval
    // frequently enough to pickup forceRun reasonably fast. Tighten down for the rare fast timer.
    //-------------------------------------------------------------------------------------------
    private void onCoreTimer() {
        if (!isRunning) {
            if ((intervalMs > 0 && intervalElapsed(intervalMs, lastRunCompleted)) || forceRun) {
                boolean wasForced = forceRun
                doRun()
                if (wasForced) forceRun = false
            }
        }
    }

    private Long calcCoreIntervalMs() {
        return (intervalMs > 2 * SECONDS) ? 1 * SECONDS : 250;
    }

    private void adjustCoreTimerIfNeeded() {
        long newCoreIntervalMs = calcCoreIntervalMs()
        if (newCoreIntervalMs != coreIntervalMs) {
            coreTimer.cancel()
            coreTimer = new java.util.Timer()
            coreTimer.schedule((this.&onCoreTimer as TimerTask), 0, newCoreIntervalMs)
            coreIntervalMs = newCoreIntervalMs
        }
    }
}
