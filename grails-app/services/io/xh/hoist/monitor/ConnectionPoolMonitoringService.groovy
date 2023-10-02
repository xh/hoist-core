/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import io.xh.hoist.BaseService
import io.xh.hoist.exception.DataNotAvailableException
import org.apache.tomcat.jdbc.pool.DataSource as PooledDataSource
import org.apache.tomcat.jdbc.pool.PoolConfiguration
import org.springframework.boot.jdbc.DataSourceUnwrapper

import javax.sql.DataSource
import java.util.concurrent.ConcurrentHashMap

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static java.lang.System.currentTimeMillis

/**
 * Service to sample and return simple statistics on JDBC connection pool usage from the app's
 * primary injected DataSource. Collects rolling history of snapshots on a configurable timer.
 */
class ConnectionPoolMonitoringService extends BaseService {

    def configService,
        dataSource

    private Map<Long, Map> _snapshots = new ConcurrentHashMap()
    private PooledDataSource _pooledDataSource
    private boolean unwrapAttempted = false

    void init() {
        createTimer(
            interval: {enabled ? config.snapshotInterval * SECONDS: -1},
            runFn: this.&takeSnapshot
        )
    }

    boolean isEnabled() {
        return config.enabled && pooledDataSource
    }

    /** Connection pool config properties, typically specified in Runtime.groovy. */
    PoolConfiguration getPoolConfiguration() {
        return pooledDataSource?.poolProperties
    }

    /** Map of previous pool usage snapshots, keyed by ms timestamp of snapshot. */
    Map getSnapshots() {
        return _snapshots
    }

    Map getLatestSnapshot() {
        return _snapshots?.max { it.key }.value
    }

    /** Take a snapshot of pool usage, add to in-memory history, and return. */
    Map takeSnapshot() {
        ensureEnabled()

        def newSnap = getSnap()
        _snapshots[newSnap.timestamp] = newSnap

        // Don't allow snapshot history to grow endlessly -
        // default cap @ 1440 samples, i.e. 24 hours * 60 snaps/hour
        if (_snapshots.size() > (config.maxSnapshots ?: 1440)) {
            def oldest = _snapshots.min {it.key}
            _snapshots.remove(oldest.key)
        }

        if (config.writeToLog) {
            logInfo(newSnap)
        }

        return newSnap
    }

    /**
     * Reset internal stats tracking on the pool.
     * Note this is distinct from clearing the cache we maintain here of historical snapshots.
     */
    Map resetStats() {
        ensureEnabled()
        pooledDataSource.pool.resetStats()
        takeSnapshot()
    }

    //------------------------
    // Implementation
    //------------------------
    private Map getSnap() {
        def ds = pooledDataSource
        return [
            timestamp: currentTimeMillis(),
            size: ds.size,
            active: ds.active,
            idle: ds.idle,
            waitCount: ds.waitCount,
            borrowed: ds.borrowedCount,
            returned: ds.returnedCount,
            created: ds.createdCount,
            released: ds.releasedCount,
            reconnected: ds.reconnectedCount,
            removeAbandoned: ds.removeAbandonedCount,
            releasedIdle: ds.releasedIdleCount
        ]
    }

    private PooledDataSource getPooledDataSource() {
        if (!_pooledDataSource && !unwrapAttempted) {
            try {
                unwrapAttempted = true
                _pooledDataSource = DataSourceUnwrapper.unwrap(dataSource as DataSource, PooledDataSource.class)
            } catch (e) {
                logError("Failed to unwrap primary Datasource to org.apache.tomcat.jdbc.pool.DataSource - cannot monitor connection pool usage.", e)
            }
        }
        return _pooledDataSource
    }

    private void ensureEnabled() {
        if (!enabled) throw new DataNotAvailableException("Unable to monitor connection pool usage - service disabled via config, or no suitable DataSource detected.")
    }

    private Map getConfig() {
        return configService.getMap('xhConnPoolMonitoringConfig')
    }

    void clearCaches() {
        this._snapshots.clear()
        super.clearCaches()
    }

    Map getAdminStats() {[
        config: config,
        latestSnapshot: latestSnapshot,
        timer: timers[0]?.adminStats
    ]}
}
