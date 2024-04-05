/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import com.sun.management.HotSpotDiagnosticMXBean
import io.xh.hoist.BaseService

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.HOURS
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static io.xh.hoist.util.Utils.startupTime
import static java.lang.Runtime.getRuntime
import static java.lang.System.currentTimeMillis

/**
 * Service to sample and return simple statistics on heap (memory) usage from the JVM runtime.
 * Collects rolling history of snapshots on a configurable timer.
 */
class MemoryMonitoringService extends BaseService {

    def configService

    private Map<Long, Map> _snapshots = new ConcurrentHashMap()
    private Date _lastInfoLogged

    void init() {
        createTimer(
            interval: {config.enabled ? config.snapshotInterval * SECONDS: -1},
            runFn: this.&takeSnapshot
        )
    }

    /**
     * Returns a map of previous JVM memory usage snapshots, keyed by ms timestamp of snapshot.
     */
    Map getSnapshots() {
        return _snapshots
    }

    Map getLatestSnapshot() {
        return _snapshots?.max {it.key}?.value
    }

    /**
     * Dump the heap to a file for analysis.
     */
    void dumpHeap(String filename) {
        String heapDumpDir = config.heapDumpDir
        if (!heapDumpDir) {
            throw new RuntimeException(
                "Unable to dump heap. Please specify value for 'xhMemoryMonitor.heapDumpDir'"
            )
        }
        if (!heapDumpDir.endsWith(File.separator)) {
            heapDumpDir += File.separator
        }
        filename = heapDumpDir + filename
        if (!filename.endsWith('.hprof')) {
            filename += '.hprof'
        }
        withInfo(['Dumping Heap', [filename: filename]]) {
            HotSpotDiagnosticMXBean mxBean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
            mxBean.dumpHeap(filename, true)
        }
    }

    /**
     * Take a snapshot of JVM memory usage, store in this service's in-memory history, and return.
     */
    Map takeSnapshot() {
        def newSnap = getSnap()

        _snapshots[newSnap.timestamp] = newSnap

        // Don't allow snapshot history to grow endlessly -
        // default cap @ 1440 samples, i.e. 24 hours * 60 snaps/hour
        if (_snapshots.size() > (config.maxSnapshots ?: 1440)) {
            def oldest = _snapshots.min {it.key}
            _snapshots.remove(oldest.key)
        }

        if (newSnap.usedPctMax > 90) {
            logWarn(newSnap)
            logWarn("MEMORY USAGE ABOVE 90%")
        } else if (intervalElapsed(1 * HOURS, _lastInfoLogged)) {
            logInfo(newSnap)
            _lastInfoLogged = new Date()
        } else {
            logDebug(newSnap)
        }

        return newSnap
    }

    /**
     * Request an immediate garbage collection, with before and after usage snapshots.
     */
    Map requestGc() {
        def before = takeSnapshot()
        System.gc()
        def after = takeSnapshot()
        return [
            before: before,
            after: after,
            elapsedMs: after.timestamp - before.timestamp
        ]
    }

    //------------------------
    // Implementation
    //------------------------
    private Map getSnap() {
        def mb = 1024 * 1024,
            timestamp = currentTimeMillis(),
            gcStats = getGCStats(timestamp),
            total = runtime.totalMemory(),
            free = runtime.freeMemory(),
            max = runtime.maxMemory(),
            used = total - free

        return [
            timestamp: timestamp,
            totalHeapMb: roundTo2DP(total / mb),
            maxHeapMb: roundTo2DP(max / mb),
            usedHeapMb: roundTo2DP(used / mb),
            freeHeapMb: roundTo2DP(free / mb),
            usedPctTotal: roundTo2DP((used * 100) / total),
            usedPctMax: roundTo2DP((used * 100) / max),
            *:gcStats
        ]
    }

    private Map getGCStats(Long timestamp) {
        long totalCollectionTime = 0,
            totalCollectionCount = 0
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            totalCollectionTime += bean.collectionTime
            totalCollectionCount += bean.collectionCount
        }

        // Convert to delta's from last snapshot, if there is one.
        def last = _snapshots.max {it.key}?.value

        long collectionCount = totalCollectionCount - (last ? last.totalCollectionCount : 0),
            collectionTime = totalCollectionTime - (last ? last.totalCollectionTime : 0),
            elapsedTime = timestamp - (last ? last.timestamp : startupTime.toInstant().toEpochMilli())

        def avgCollectionTime = collectionCount ? Math.round(collectionTime/collectionCount) : 0

        def pctCollectionTime = elapsedTime ? roundTo2DP((collectionTime*100)/elapsedTime) : null

        return [
            totalCollectionTime: totalCollectionTime,
            totalCollectionCount: totalCollectionCount,
            collectionTime: collectionTime,
            collectionCount: collectionCount,
            avgCollectionTime: avgCollectionTime,
            pctCollectionTime: pctCollectionTime
        ]
    }

    private Map getConfig() {
        return configService.getMap('xhMemoryMonitoringConfig')
    }

    private double roundTo2DP(v) {
        return Math.round(v * 100) / 100
    }

    void clearCaches() {
        _snapshots.clear()
        super.clearCaches()
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhMemoryMonitoringConfig'),
        latestSnapshot: latestSnapshot,
    ]}
}