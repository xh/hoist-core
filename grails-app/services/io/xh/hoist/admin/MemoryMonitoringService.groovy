/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import com.sun.management.HotSpotDiagnosticMXBean
import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import io.xh.hoist.util.DateTimeUtils

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap

import static io.xh.hoist.json.JSONParser.parseObject
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static io.xh.hoist.util.Utils.getAppEnvironment
import static io.xh.hoist.util.Utils.isProduction
import static io.xh.hoist.cluster.ClusterService.startupTime
import static io.xh.hoist.util.DateTimeUtils.HOURS
import static java.lang.Runtime.getRuntime
import static java.lang.System.currentTimeMillis


/**
 * Service to sample and return simple statistics on heap (memory) usage from the JVM runtime.
 * Collects rolling history of snapshots on a configurable timer.
 */
class MemoryMonitoringService extends BaseService {

    def configService
    def jsonBlobService

    private Map<Long, Map> _snapshots = new ConcurrentHashMap()
    private Date _lastInfoLogged
    private final String blobOwner = 'xhMemoryMonitoringService'
    private final static String blobType =  isProduction ? 'xhMemorySnapshots' : "xhMemorySnapshots_$appEnvironment"

    void init() {
        createTimer(
            name: 'takeSnapshot',
            runFn: this.&takeSnapshot,
            interval: {this.enabled ? config.snapshotInterval * DateTimeUtils.SECONDS: -1}
        )

        createTimer(
            name: 'cullPersisted',
            runFn: this.&cullPersisted,
            interval: 1 * HOURS,
            delay: 5 * MINUTES,
            primaryOnly: true
        )
    }

    boolean getEnabled() {
        return config.enabled
    }

    /** Returns map of previous JVM memory usage snapshots, keyed by ms timestamp of snapshot. */
    Map getSnapshots() {
        return _snapshots
    }

    Map getLatestSnapshot() {
        return _snapshots?.max {it.key}?.value
    }

    /** Dump the heap to a file for analysis. */
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

    /** Take snapshot of JVM memory usage, add to this service's in-memory history, and return. */
    Map takeSnapshot() {
        def newSnap = getSnap()

        _snapshots[newSnap.timestamp] = newSnap

        // Don't allow snapshot history to grow endlessly -
        // default cap @ 1440 samples, i.e. 24 hours * 60 snaps/hour
        if (_snapshots.size() > (config.maxSnapshots ?: 1440)) {
            def oldest = _snapshots.min {it.key}
            _snapshots.remove(oldest.key)
        }

        if (intervalElapsed(1 * HOURS, _lastInfoLogged)) {
            logInfo(newSnap)
            _lastInfoLogged = new Date()
        } else {
            logDebug(newSnap)
        }

        if (config.preservePastInstances) persistSnapshots()

        return newSnap
    }

    /** Request an immediate garbage collection, then return before and after usage snapshots. */
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

    /**
     * Get list of past instances for which snapshots are available.
     */
    List<Map> availablePastInstances() {
        if (!config.preservePastInstances) return []
        jsonBlobService
            .list(blobType, blobOwner)
            .findAll { !clusterService.isMember(it.name) }
            .collect { [name: it.name, lastUpdated: it.lastUpdated] }
    }

    /**
     * Get snapshots for a past instance.
     */
    Map snapshotsForPastInstance(String instanceName) {
        def blob = jsonBlobService.list(blobType, blobOwner).find { it.name == instanceName }
        blob ? parseObject(blob.value) : [:]
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

    private void persistSnapshots() {
        try {
            jsonBlobService.createOrUpdate(
                blobType,
                clusterService.instanceName,
                [value: snapshots],
                blobOwner
            )
        } catch (Exception e) {
            logError('Failed to persist memory snapshots', e)
        }
    }

    @Transactional
    private cullPersisted() {
        def all = jsonBlobService.list(blobType, blobOwner).sort { it.lastUpdated },
            maxKeep = config.maxPastInstances != null ? Math.max(config.maxPastInstances, 0) : 5,
            toDelete = all.dropRight(maxKeep)

        if (toDelete) {
            withInfo(['Deleting memory snapshots', [count: toDelete.size()]]) {
                toDelete.each { it.delete() }
            }
        }
    }

    void clearCaches() {
        _snapshots.clear()
        super.clearCaches()
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhMemoryMonitoringConfig'),
        latestSnapshot: latestSnapshot
    ]}
}
