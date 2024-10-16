/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor.provided

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import groovy.sql.Sql
import io.xh.hoist.BaseService
import io.xh.hoist.monitor.Monitor
import io.xh.hoist.monitor.MonitorResult
import io.xh.hoist.util.Utils
import io.xh.hoist.clienterror.ClientError
import io.xh.hoist.track.TrackLog

import static io.xh.hoist.monitor.MonitorStatus.FAIL
import static io.xh.hoist.monitor.MonitorStatus.INACTIVE
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static java.lang.System.currentTimeMillis

/**
 * Optional default implementation of MonitorDefinitionService for applications that wish to
 * leverage Hoist's built-in, database-backed monitoring and its associated Admin Console UI.
 *
 * Applications using this default implementation must either:
 *
 *  1) Define a `MonitoringDefinitionService` class that extends this class.
 *     Allows for overriding its protected methods to customize behavior.
 *
 *  2) Register this class directly as `monitoringDefinitionService` via `resources.groovy`,
 *     if no app-specific monitors are required.
 */
class DefaultMonitorDefinitionService extends BaseService {

    def getConfigService() { Utils.configService }
    def getDataSource() {Utils.dataSource}
    def getLdapService() { Utils.ldapService}
    def getMemoryMonitoringService() { Utils.appContext.memoryMonitoringService}
    def getTrackLogAdminService() { Utils.appContext.trackLogAdminService}

    void init() {
        ensureRequiredConfigAndMonitorsCreated()
    }

    def xhMemoryMonitor(MonitorResult result) {
        if (!memoryMonitoringService.enabled) {
            result.status = INACTIVE
            return
        }

        def aggregate = result.getParam('aggregate', 'avg')
        if (!(aggregate in ['avg', 'max'])) {
            throw new RuntimeException("Invalid aggregate parameter: $aggregate")
        }

        def lookback = result.getRequiredParam('lookbackMinutes') * MINUTES,
            cutoffTime = currentTimeMillis() - lookback,
            snapshots = memoryMonitoringService.snapshots.findAll {it.key > cutoffTime}.values()

        if (!snapshots) {
            result.metric = 0
            return
        }

        result.metric = aggregate == 'avg'
            ? snapshots.average{it.usedPctMax}.round(2)
            : snapshots.max{it.usedPctMax}.usedPctMax
    }

    @ReadOnly
    def xhClientErrorsMonitor(MonitorResult result) {
        def lookback = result.getRequiredParam('lookbackMinutes') * MINUTES,
            cutoffDate = new Date(currentTimeMillis() - lookback)

        result.metric = ClientError.countByDateCreatedGreaterThan(cutoffDate)
    }

    @ReadOnly
    def xhLoadTimeMonitor(MonitorResult result) {
        if (!trackLogAdminService.enabled) {
            result.status = INACTIVE
            return
        }

        def lookback = result.getRequiredParam('lookbackMinutes') * MINUTES,
            cutoffDate = new Date(currentTimeMillis() - lookback),
            logs = TrackLog.findAllByDateCreatedGreaterThanAndElapsedIsNotNull(cutoffDate)

        result.metric = logs ? logs.max{it.elapsed}.elapsed / SECONDS : 0
    }

    def xhDbConnectionMonitor(MonitorResult result) {
        def startTime = currentTimeMillis()
        Sql sql = new Sql(dataSource)
        try {
            // Support configurable table name for edge case where XH tables are in a custom schema.
            def tableName = result.getParam('tableName', 'xh_monitor')
            sql.rows("SELECT * FROM ${Sql.expand(tableName)} WHERE code = 'xhDbConnectionMonitor'")
        } finally {
            sql.close()
        }

        result.metric = currentTimeMillis() - startTime
    }

    def xhLdapServiceConnectionMonitor(MonitorResult result) {
        if (!ldapService.enabled) {
            result.status = INACTIVE
            return
        }

        def queryUser = result.getRequiredParam('queryUser'),
            user = ldapService.lookupUser(queryUser),
            startTime = currentTimeMillis()

        if (!user) {
            result.message = "Failed to find expected user: ${result.params.queryUser}"
            result.status = FAIL
        }

        result.metric = currentTimeMillis() - startTime
    }

    /**
     * Ensure that the required soft-config entry for this service has been created, along with a
     * minimal set of required Hoist monitors. Called by init() on app startup.
     */
    protected void ensureRequiredConfigAndMonitorsCreated() {
        configService.ensureRequiredConfigsCreated([
            xhMonitorConfig: [
                valueType: 'json',
                monitorRefreshMins: 5,
                monitorStartupDelayMins: 1,
                warnNotifyThreshold: 5,
                failNotifyThreshold: 2,
                monitorRepeatNotifyMins: 60
            ]
        ])

        ensureRequiredMonitorsCreated([
            [
                code: 'xhMemoryMonitor',
                name: 'Avg Heap Usage % (Last 30m)',
                metricType: 'Ceil',
                metricUnit: '%',
                warnThreshold: 75,
                failThreshold: 90,
                active: true,
                params: '{\n\t"lookbackMinutes": 30,\n\t"aggregate": "avg"\n}',
                notes: 'Reports the largest heap usage in the last {lookbackMinutes} minutes.\n'
                        + 'Set "aggregate" to "avg" to report average heap usage (default).\n'
                        + 'Set "aggregate" to "max" to report the largest heap usage.'
            ],
            [
                code: 'xhClientErrorsMonitor',
                name: 'Client Errors (Last 30m)',
                metricType: 'Ceil',
                metricUnit: 's',
                warnThreshold: 1,
                failThreshold: 10,
                active: true,
                primaryOnly: true,
                params: '{\n\t"lookbackMinutes": 30\n}',
                notes: 'Reports the longest tracked event in the last {lookbackMinutes} minutes.'
            ],
            [
                code: 'xhLoadTimeMonitor',
                name: 'Max Load Time (Last 30m)',
                metricType: 'Ceil',
                metricUnit: 's',
                warnThreshold: 30,
                failThreshold: 60,
                active: true,
                primaryOnly: true,
                params: '{\n\t"lookbackMinutes": 30\n}',
                notes: 'Reports the longest tracked event in the last {lookbackMinutes} minutes.'
            ],
            [
                code: 'xhDbConnectionMonitor',
                name: 'DB Connection Time',
                metricType: 'Ceil',
                warnThreshold: 5000,
                failThreshold: 10000,
                metricUnit: 'ms',
                active: true,
                notes: 'Reports time taken to query primary application database with a trivial select statement.'
            ],
            [
                code: 'xhLdapServiceConnectionMonitor',
                name: 'LDAP Connection Time',
                metricType: 'Ceil',
                warnThreshold: 5000,
                failThreshold: 10000,
                metricUnit: 'ms',
                active: true,
                params: '{\n\t"queryUser": "admin"\n}',
                notes: 'Reports time taken to query Hoist LdapService for the configured user, to test connectivity to an external directory (if enabled).'
            ]
        ])
    }

    /**
     * Check a list of core monitors provided for Hoist/application monitors - ensuring that these
     * monitors are present. Will create missing monitors with supplied default values if not found.
     *
     * May be called within an implementation of ensureRequiredConfigAndMonitorsCreated().
     *
     * @param requiredMonitors - List of maps of [code, name, metricType, active]
     */
    @Transactional
    void ensureRequiredMonitorsCreated(List<Map> monitorSpecs) {
        List<Monitor> currMonitors = Monitor.list()
        int created = 0

        monitorSpecs.each {spec ->
            try {
                Monitor currMonitor = currMonitors.find { it.code == spec.code }
                if (!currMonitor) {
                    new Monitor(
                        code: spec.code,
                        name: spec.name,
                        metricType: spec.metricType,
                        active: spec.active,
                        metricUnit: spec.metricUnit,
                        warnThreshold: spec.warnThreshold,
                        failThreshold: spec.failThreshold,
                        primaryOnly: spec.primaryOnly,
                        params: spec.params,
                        notes: spec.notes
                    ).save()
                    logWarn(
                        "Required monitor ${spec.name} missing and created with default value",
                        'verify default is appropriate for this application'
                    )
                    created++
                }
            } catch (Throwable e) {
                logError("Failed to create required monitor ${spec.name}", e)
            }
        }
        logDebug("Validated presense of ${monitorSpecs.size()} provided monitors", "created $created")
    }
}
