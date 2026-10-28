/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.exception.DataNotAvailableException
import org.apache.tomcat.jdbc.pool.DataSource as PooledDataSource
import org.apache.tomcat.jdbc.pool.PoolConfiguration

import javax.sql.DataSource
import java.util.concurrent.ConcurrentHashMap

import static io.xh.hoist.util.DataSourceUtils.findPooledDataSource
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.getHOURS
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static io.xh.hoist.util.Utils.asSanitizedJSON
import static java.lang.System.currentTimeMillis

/**
 * Service to sample and return simple statistics on JDBC connection pool usage from the app's
 * primary injected DataSource. Collects rolling history of snapshots on a configurable timer.
 */
class ConnectionPoolMonitoringService extends BaseService {

    ConfigService configService
    DataSource dataSource
    def grailsApplication

    private Map<Long, Map> _snapshots = new ConcurrentHashMap()
    private Date _lastInfoLogged
    private PooledDataSource pooledDataSource

    void init() {
        pooledDataSource = findPooledDataSource(dataSource)
        if (!pooledDataSource) {
            logError("Failed to unwrap primary Datasource to org.apache.tomcat.jdbc.pool.DataSource - cannot monitor connection pool usage.")
        }
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
        if (_snapshots.size() > config.maxSnapshots) {
            def oldest = _snapshots.min {it.key}
            _snapshots.remove(oldest.key)
        }

        if (config.writeToLog) {
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
        pooledDataSource?.pool?.resetStats()
        takeSnapshot()
    }

    /**
     * Close all pooled JDBC connections on this instance, across all datasources. Idle connections
     * close immediately, in-use connections close on return. Use to discard connections holding
     * stale session state, e.g. DB permissions granted after the pool was populated.
     *
     * @return connection counts before and after, keyed by datasource bean name.
     */
    Map purgeConnections() {
        def ret = pooledDataSources.collectEntries { name, ds ->
            def before = [size: ds.size, active: ds.active, idle: ds.idle]
            ds.pool.purge()
            def after = [size: ds.size, active: ds.active, idle: ds.idle]
            logInfo('Purged connection pool', [dataSource: name, before: before, after: after])
            [name, [before: before, after: after]]
        }
        if (enabled) takeSnapshot()
        return ret
    }

    //------------------------
    // Implementation
    //------------------------

    /**
     * All Tomcat pools reachable from Spring-managed DataSource beans, keyed by bean name.
     * Multiple beans typically front the same pool (e.g. `dataSource` and `dataSourceUnproxied`) -
     * each pool is returned once, under the first bean name in sort order.
     */
    private Map<String, PooledDataSource> getPooledDataSources() {
        def ctx = grailsApplication.mainContext,
            ret = [:] as Map<String, PooledDataSource>
        ctx.getBeanNamesForType(DataSource).sort().each { name ->
            def pool = findPooledDataSource(ctx.getBean(name) as DataSource)
            if (pool && !ret.values().any { it.is(pool) }) ret[name] = pool
        }
        return ret
    }

    private Map getSnap() {
        def ds = pooledDataSource
        return [
            timestamp: currentTimeMillis(),
            size: ds?.size,
            active: ds?.active,
            idle: ds?.idle,
            waitCount: ds?.waitCount,
            borrowed: ds?.borrowedCount,
            returned: ds?.returnedCount,
            created: ds?.createdCount,
            released: ds?.releasedCount,
            reconnected: ds?.reconnectedCount,
            removeAbandoned: ds?.removeAbandonedCount,
            releasedIdle: ds?.releasedIdleCount
        ]
    }

    private void ensureEnabled() {
        if (!enabled) throw new DataNotAvailableException("Unable to monitor connection pool usage - service disabled via config, or no suitable DataSource detected.")
    }

    private ConnPoolMonitoringConfig getConfig() {
        return configService.getObject(ConnPoolMonitoringConfig)
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
