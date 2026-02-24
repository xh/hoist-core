/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.xh.hoist.BaseService
import io.xh.hoist.exception.DataNotAvailableException
import io.xh.hoist.telemetry.MetricsService
import org.apache.tomcat.jdbc.pool.DataSource as PooledDataSource
import org.apache.tomcat.jdbc.pool.PoolConfiguration
import org.springframework.boot.jdbc.DataSourceUnwrapper

import javax.sql.DataSource
import java.util.concurrent.ConcurrentHashMap

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.getHOURS
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static io.xh.hoist.util.Utils.asSanitizedJSON
import static java.lang.Double.NaN
import static java.lang.System.currentTimeMillis

/**
 * Service to sample and return simple statistics on JDBC connection pool usage from the app's
 * primary injected DataSource. Collects rolling history of snapshots on a configurable timer.
 */
class ConnectionPoolMonitoringService extends BaseService {

    def configService,
        dataSource

    MetricsService metricsService

    private Map<Long, Map> _snapshots = new ConcurrentHashMap()
    private Date _lastInfoLogged
    private PooledDataSource _pooledDataSource
    private boolean unwrapAttempted = false

    void init() {
        initMetrics()
        createTimer(
            name: 'takeSnapshot',
            runFn: this.&takeSnapshot,
            interval: {enabled ? config.snapshotInterval * SECONDS: -1}
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

    Map getSnapshotsForAdmin() {
        return [
            enabled          : enabled,
            snapshots        : snapshots,
            poolConfiguration: asSanitizedJSON(poolConfiguration)
        ]
    }

    Map getLatestSnapshot() {
        return _snapshots?.max { it.key }?.value
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

        if (config.writeToLog !== false) {
            if (intervalElapsed(1 * HOURS, _lastInfoLogged)) {
                logInfo(newSnap)
                _lastInfoLogged = new Date()
            } else {
                logDebug(newSnap)
            }
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
    private void initMetrics() {

        def prefix = 'jdbc.pool'
        List<Meter> meters = [
            // Live pool state
            Gauge.builder("${prefix}.size", this, readDsProp('size'))
                .description('Total connections in pool (active + idle)'),
            Gauge.builder("${prefix}.active", this, readDsProp('active'))
                .description('Active/in-use connections'),
            Gauge.builder("${prefix}.idle", this, readDsProp('idle'))
                .description('Idle connections'),
            Gauge.builder("${prefix}.waitCount", this, readDsProp('waitCount'))
                .description('Threads waiting for a connection'),

            // Cumulative pool counters
            FunctionCounter.builder("${prefix}.borrowed", this, readDsProp('borrowedCount'))
                .description('Connections borrowed from pool'),
            FunctionCounter.builder("${prefix}.returned", this, readDsProp('returnedCount'))
                .description('Connections returned to pool'),
            FunctionCounter.builder("${prefix}.created", this, readDsProp('createdCount'))
                .description('Connections created'),
            FunctionCounter.builder("${prefix}.released", this, readDsProp('releasedCount'))
                .description('Connections released/destroyed'),
            FunctionCounter.builder("${prefix}.reconnected", this, readDsProp('reconnectedCount'))
                .description('Connections re-established after failure'),
            FunctionCounter.builder("${prefix}.removeAbandoned", this, readDsProp('removeAbandonedCount'))
                .description('Connections removed due to abandonment'),
            FunctionCounter.builder("${prefix}.releasedIdle", this, readDsProp('releasedIdleCount'))
                .description('Idle connections released by evictor')
        ]

        meters.each {
            it.tag('source', 'infra')
            it.register(metricsService.registry)
        }

    }

    private Closure readDsProp(String prop) {
        return {
            pooledDataSource ? pooledDataSource[prop] as double : NaN
        }
    }

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
        config: configForAdminStats('xhConnPoolMonitoringConfig'),
        latestSnapshot: latestSnapshot
    ]}
}
