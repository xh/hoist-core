/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import io.xh.hoist.BaseService
import io.xh.hoist.util.Timer

import java.util.concurrent.ConcurrentHashMap

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.ONE_MINUTE
import static io.xh.hoist.util.DateTimeUtils.ONE_HOUR
import static java.lang.Runtime.getRuntime

/**
 * Service to sample and return simple statistics on heap (memory) usage from the JVM runtime.
 * Collects rolling history of snapshots on a configurable timer.
 */
class MemoryMonitoringService extends BaseService {

    private Map<Long, Map> _snapshots = new ConcurrentHashMap()
    private Timer _snapshotTimer
    private Timer _infoTimer
    private Timer _debugTimer
    private Timer _warnTimer

    void init() {
        _snapshotTimer = createTimer(
            interval: 'xhMemoryMonitorIntervalSecs',
            intervalUnits: SECONDS,
            runFn: this.&takeSnapshot
        )

        _infoTimer = createTimer(
                interval: ONE_HOUR,
                runFn: this.&onInfoTimer
        )

        _debugTimer = createTimer(
                interval: ONE_MINUTE,
                runFun: this.&onDebugTimer
        )

        _warnTimer = createTimer(
                interval: ONE_MINUTE,
                runFn: this.&onWarnTimer
        )
    }

    private void onInfoTimer() {
        def newest = _snapshots.get(_snapshots.keys().toList().max())
        def totalHeap = newest.get('totalHeapMb')
        def maxHeap = newest.get('maxHeapMb')
        def usedHeap = newest.get('usedHeapMb')
        def freeHeap = newest.get('freeHeapMb')
        def usedPctTotal = newest.get('usedPctTotal')
        logInfo("Total=${totalHeap}MB | Max=${maxHeap}MB | Used=${usedHeap}MB | Free=${freeHeap}MB | Used Percent of " +
                "Total=${usedPctTotal}%")
    }

    private void onDebugTimer() {
        logDebug("debug")
    }

    private void onWarnTimer() {
        def newest = _snapshots.get(_snapshots.keys().toList().max())
        def usedPctTotal = newest.get('usedPctTotal')
        if(usedPctTotal > 90) {
            logWarn("MEMORY USAGE ABOVE 90%")
        }
    }

    /**
     * Returns a map of previous JVM memory usage snapshots, keyed by ms timestamp of snapshot.
     */
    Map getSnapshots() {
        return _snapshots
    }

    /**
     * Take a snapshot of JVM memory usage, store in this service's in-memory history, and return.
     */
    Map takeSnapshot() {
        def newSnap = getStats()
        _snapshots.put(System.currentTimeMillis(), newSnap)

        // Don't allow snapshot history to grow endlessly - cap @ 1440 samples, i.e. 24 hours of
        // history if left at default config interval of one snap/minute.
        if (_snapshots.size() > 1440) {
            def oldest = _snapshots.keys().toList().min()
            _snapshots.remove(oldest)
        }

        return newSnap
    }

    /**
     * Request an immediate garbage collection, with before and after usage snapshots.
     */
    Map requestGc() {
        def before = takeSnapshot(),
            start = System.currentTimeMillis()

        System.gc()

        def after = takeSnapshot()
        return [
            before: before,
            after: after,
            elapsedMs: System.currentTimeMillis() - start
        ]
    }


    //------------------------
    // Implementation
    //------------------------
    private Map getStats() {
        def mb = 1024 * 1024,
            total = runtime.totalMemory(),
            free = runtime.freeMemory(),
            used = total - free,
            max = runtime.maxMemory(),
            round = {it -> Math.round(it * 100) / 100}

        return [
            totalHeapMb: round(total / mb),
            maxHeapMb: round(max / mb),
            usedHeapMb: round(used / mb),
            freeHeapMb: round(free / mb),
            usedPctTotal: round((used * 100) / total),
            totalPctMax: round((total * 100) / max)
        ]
    }

    void clearCaches() {
        this._snapshots.clear()
    }
}
