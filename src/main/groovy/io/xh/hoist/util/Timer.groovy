/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import io.xh.hoist.async.AsyncSupport
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
import static io.xh.hoist.util.Utils.withNewSession

class Timer implements AsyncSupport {

    private static Long CORE_INTERVAL = ONE_SECOND
    private static Long CONFIG_INTERVAL = 15 * SECONDS

    // Note that 'delay', 'interval', and 'timeout' can be specified as a long, a closure, or a config name to be
    // looked up in config service.   The last two options offer the possibility of 'hot' changes to the timers config.

    // Required arguments
    public final LogSupport owner               //  object using this timer (for logging purposes)
    public final Object interval                //  interval,  If <= 0, job will not run
    public final Closure runFn                  //  closure to run

    // Optional arguments
    public final String name                    //  name for status logging disambiguation [default 'anon']
    public final Object delay                   //  delay [default 0]
    public final Object timeout                 //  max time to let function run before cancelling, and attempting a later run (default 3 mins)
    public final Long delayUnits                //  number to multiply delay by to get millis, [default 1]
    public final Long intervalUnits             //  number to multiply interval by to get millis, [default 1]
    public final Long timeoutUnits              //  number to multiply timeout by to get millis, [default 1]
    public final boolean withHibernate          //  run the function on a thread with hibernate session? (default true)
    public final boolean runImmediatelyAndBlock //  block on an immediate initial run [default false]

    // NOTE that even when runImmediatelyAndBlock is false, the task may be run *nearly* immediately
    // but asynchronously, as governed by delay
    private Long intervalMs
    private Long delayMs
    private Long timeoutMs

    private Date lastRun = null
    private boolean isRunning = false
    private boolean forceRun  = false
    private java.util.Timer coreTimer
    private java.util.Timer configTimer

    // Args from Grails 3.0 async promise implementation
    static ExecutorService executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>())

    Timer(Map config) {
        name = config.name ?: 'anon'
        owner = config.owner
        runFn = config.runFn
        runImmediatelyAndBlock = config.runImmediatelyAndBlock ?: false
        withHibernate = config.containsKey('withHibernate') ? config.withHibernate : true

        interval = config.interval
        delay = config.delay && !runImmediatelyAndBlock ? config.delay : 0
        timeout = config.containsKey('timeout') ? config.timeout : 3 * MINUTES

        // Post-process config strings to canonical closures
        interval = processConfigString(interval)
        delay = processConfigString(delay)
        timeout = processConfigString(timeout)

        intervalUnits = config.intervalUnits ?: 1
        delayUnits = config.delayUnits ?: 1
        timeoutUnits = config.timeoutUnits ?: 1

        if ([owner, interval, runFn].contains(null)) throw new RuntimeException('Missing required arguments for Timer.')

        intervalMs = calcIntervalMs()
        delayMs = calcDelayMs()
        timeoutMs = calcTimeoutMs()

        if (intervalMs > 0 && intervalMs < CORE_INTERVAL * 2) {
            throw new RuntimeException('Object not appropriate for very short intervals.  Use a java.util.Timer instead.')
        }

        if (runImmediatelyAndBlock) {
            doRun()
        }

        // Main Timer
        coreTimer = new java.util.Timer()
        coreTimer.schedule(
                (this.&onCoreTimer as TimerTask), delayMs, CORE_INTERVAL
        )

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
        this.forceRun = true
    }

    /**
     * Cancel this timer.
     *
     * This will prevent any additional executions of this timer.  In-progress executions will be unaffected.
     */
    void cancel() {
        if (coreTimer) coreTimer.cancel()
        if (configTimer) configTimer.cancel()
    }

    
    //------------------------
    // Implementation
    //------------------------
    private void onCoreTimer() {
        if (!isRunning) {
            if ((intervalMs > 0 && intervalElapsed(intervalMs, lastRun)) || forceRun) {
                boolean wasForced = forceRun
                doRun()
                if (wasForced) forceRun = false
            }
        }
    }

    private void doRun() {
        isRunning = true
        Throwable throwable = null
        Future future = null
        try {
            def callable = withHibernate ? {withNewSession {runFn()}} : runFn
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

        lastRun = new Date()
        isRunning = false

        if (throwable) {
            try {
                owner.logErrorCompact("Failure in Timer [$name]", throwable)
            } catch (Throwable ignore) {}
        }
    }

    //-------------------------------
    // Config, interval Management
    //------------------------------
    private Long calcIntervalMs() {
        switch (interval) {
            case null:      return null
            case Closure:   return (interval as Closure)() * intervalUnits
            default:        return interval * intervalUnits
        }
    }

    private Long calcTimeoutMs() {
        switch (timeout) {
            case null:      return null
            case Closure:   return (timeout as Closure)() * timeoutUnits
            default:        return timeout * timeoutUnits
        }
    }

    private Long calcDelayMs() {
        switch (delay) {
            case null:      return null
            case Closure:   return (delay as Closure)() * delayUnits
            default:        return delay * delayUnits
        }
    }

    private void onConfigTimer() {
        try {
            withNewSession {
                intervalMs = calcIntervalMs()
                timeoutMs = calcTimeoutMs()
            }
        } catch (Throwable t) {
            owner.logErrorCompact("Timer [$name] failed to reload config", t)
        }
    }

    Object processConfigString(Object obj) {
        if (obj instanceof String) {
            return {configService.getInt(obj as String)}
        }
        return obj
    }

}
