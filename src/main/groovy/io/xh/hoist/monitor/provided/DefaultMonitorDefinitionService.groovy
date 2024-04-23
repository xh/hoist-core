/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor.provided

import groovy.sql.Sql
import io.xh.hoist.BaseService
import io.xh.hoist.data.filter.Filter
import io.xh.hoist.monitor.Monitor
import io.xh.hoist.monitor.MonitorResult
import grails.gorm.transactions.Transactional
import io.xh.hoist.util.Utils

import static io.xh.hoist.monitor.MonitorStatus.FAIL
import static io.xh.hoist.monitor.MonitorStatus.INACTIVE
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static java.lang.System.currentTimeMillis



/**
 * Optional default implementation of MonitorDefinitionService for applications that wish to leverage
 * Hoist's built-in, database-backed monitoring and its associated Admin Console UI.
 *
 * Applications using this default implementation must either:
 *
 *  1) Define a `MonitoringDefinitionService` class that extends this class. Allows for overriding its protected
 *     methods to customize behavior.
 *
 *  2) Register this class directly as `monitoringDefinitionService` via `grails-app/conf/spring/resources.groovy`,
 *     assuming neither directory group support nor any further customizations are required.
 */
class DefaultMonitorDefinitionService extends BaseService {

    def configService,
    memoryMonitoringService,
    trackLogAdminService,
    ldapService,
    dataSource

    def xhMemoryMonitor(MonitorResult result) {
        if (!memoryMonitoringService.enabled) {
            result.status = INACTIVE
            return
        }
        def lookbackMinutes = result.params.lookbackMinutes
        if (!lookbackMinutes) {
            throw new RuntimeException('No \"lookbackMinutes\" parameter provided')
        }
        def cutOffTime = currentTimeMillis() - lookbackMinutes * MINUTES
        def snapshots = memoryMonitoringService.snapshots.findAll {it.key > cutOffTime}.values()
        if (!snapshots) {
            result.metric = 0
            return
        }
        if (!result.params.aggregate || result.params.aggregate == 'avg') {
            result.metric = snapshots.average{it.usedPctMax}.round(2)
        } else if (result.params.aggregate == 'max') {
            def maxPercentUsed = snapshots.max{it.usedPctMax}.usedPctMax
            result.metric = maxPercentUsed
        } else {
            throw new RuntimeException("Invalid aggregate parameter: ${result.params.aggregate}")
        }
    }

    def xhLoadTimeMonitor(MonitorResult result) {
        if (!trackLogAdminService.enabled) {
            result.status = INACTIVE
            return
        }
        def lookbackMinutes = result.params.lookbackMinutes
        if (!lookbackMinutes) {
            throw new RuntimeException('No \"lookbackMinutes\" parameter provided.')
        }
        def cutOffTime = currentTimeMillis() - lookbackMinutes * MINUTES
        def logs = trackLogAdminService.queryTrackLog(
            Filter.parse([
                filters: [
                    [
                        field: 'dateCreated',
                        op: '>',
                        value: new Date(cutOffTime)
                    ],
                    [
                        field: 'elapsed',
                        op: '!=',
                        value: null
                    ]
                ],
                op: "AND"
            ])
        )
        if (!logs) {
            result.metric = 0
            return
        }
        def maxElapsed = logs.max{it.elapsed}.elapsed / SECONDS
        result.metric = maxElapsed
    }

    def xhDbConnectionMonitor(MonitorResult result) {
        def startTime = currentTimeMillis()
        Sql sql = new Sql(dataSource)
        try {
            sql.rows("SELECT * FROM xh_monitor WHERE code = 'xhDbConnectionMonitor' LIMIT 1")
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
        if (!result.params.queryUser) {
            throw new RuntimeException("No \"queryUser\" parameter provided.")
        }
        def startTime = currentTimeMillis()
        def user = ldapService.lookupUser(result.params.queryUser)
        if (!user) {
            result.status = FAIL
            return
        }
        result.metric = currentTimeMillis() - startTime
    }

    void init() {
        ensureRequiredConfigAndMonitorsCreated()
    }

    /**
     * Ensure that the required soft-config entry for this service has been created, along with a
     * minimal set of required Hoist monitors. Called by init() on app startup.
     */
    protected void ensureRequiredConfigAndMonitorsCreated() {
        configService.ensureRequiredConfigsCreated([
            xhMonitorConfig: [
                valueType: 'json',
                monitorRefreshMins: 10,
                monitorStartupDelayMins: 1,
                warnNotifyThreshold: 5,
                failNotifyThreshold: 2,
                monitorRepeatNotifyMins: 60
            ]
        ])

        ensureRequiredMonitorsCreated([
            [
                code: 'xhMemoryMonitor',
                name: 'Avg Heap Usage % Monitor (Last 30m)',
                metricType: 'Ceil',
                metricUnit: '%',
                warnThreshold: 75,
                failThreshold: 90,
                active: true,
                primaryOnly: false,
                params: '{\n\t"lookbackMinutes": 30,\n\t"aggregate": "avg"\n}',
                notes: 'This will report the largest heap usage in the last {lookbackMinutes} minutes.\n'
                        + 'Set "aggregate" to "avg" to report average heap usage.\n'
                        + 'Set "aggregate" to "max" to report the largest heap usage.\n'
                        + 'Will report average heap usage if "aggregate" is not provided.'
            ],
            [
                code: 'xhLoadTimeMonitor',
                name: 'Max Load Time Monitor (Last 30m)',
                metricType: 'Ceil',
                metricUnit: 's',
                warnThreshold: 30,
                failThreshold: 60,
                active: true,
                primaryOnly: true,
                params: '{\n\t"lookbackMinutes": 30\n}',
                notes: 'This will report the longest tracked event in the last {lookbackMinutes} minutes.'
            ],
            [
                code: 'xhDbConnectionMonitor',
                name: 'DB Connection Time Monitor',
                metricType: 'None',
                warnThreshold: 10000,
                metricUnit: 'ms',
                active: true,
                primaryOnly: true,
            ],
            [
                code: 'xhLdapServiceConnectionMonitor',
                name: 'LDAP Service Connection Time Monitor',
                metricType: 'None',
                warnThreshold: 10000,
                metricUnit: 'ms',
                active: true,
                primaryOnly: true,
                params: '{\n\t"queryUser": "admin"\n}',
                notes: 'This will query the LDAP service for the provided user.'
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
        }
        logDebug("Validated presense of ${monitorSpecs.size()} provided monitors", "created $created")
    }
}
