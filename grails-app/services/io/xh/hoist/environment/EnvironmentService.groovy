/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.environment

import grails.util.GrailsUtil
import grails.util.Holders
import grails.util.Metadata
import io.xh.hoist.BaseService
import io.xh.hoist.alertbanner.AlertBannerConfig
import io.xh.hoist.alertbanner.AlertBannerService
import io.xh.hoist.cachedvalue.CachedValue
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.exception.NotAuthenticatedException
import io.xh.hoist.exception.NotFoundException
import io.xh.hoist.json.JSONParser
import io.xh.hoist.monitor.MonitorService
import io.xh.hoist.telemetry.metric.MetricsConfig
import io.xh.hoist.track.TrackLogAdminService
import io.xh.hoist.track.TrackService
import io.xh.hoist.util.Utils
import io.xh.hoist.websocket.WebSocketService
import jakarta.servlet.http.HttpServletRequest

import java.lang.management.ManagementFactory
import java.security.MessageDigest

import static io.xh.hoist.monitor.MonitorStatus.FAIL
import static io.xh.hoist.monitor.MonitorStatus.INACTIVE
import static io.xh.hoist.monitor.MonitorStatus.WARN
import static io.xh.hoist.util.ClusterUtils.runOnAllInstances
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.Utils.hoistCoreVersion
import static java.nio.charset.StandardCharsets.UTF_8


/**
 * Service with metadata describing the runtime environment of Hoist and this application.
 *
 * If you are simply looking to read the `AppEnvironment` (e.g. Development/Production), use
 * the static {@link io.xh.hoist.util.Utils#getAppEnvironment} instead.
 *
 * Also builds the opt-in inventory report served via the whitelisted `/xh/inventory` endpoint,
 * for automated sweeps that catalog Hoist deployments across an organization. See
 * {@link #getInventory} and the `xhInventoryConfig` soft config.
 */
class EnvironmentService extends BaseService {

    /** Sections of the inventory report, in output order. Omit any via `excludeSections`. */
    static final List<String> INVENTORY_SECTIONS = [
        'app', 'runtime', 'dependencies', 'client', 'cluster', 'database', 'features', 'monitors',
        'usage', 'libraries', 'custom'
    ]

    /** Version of the inventory report format - incremented on any incompatible change. */
    static final int INVENTORY_FORMAT_VERSION = 1

    /** Preferred request header for presenting the `xhInventoryToken`. */
    static final String INVENTORY_TOKEN_HEADER = 'X-XH-Inventory-Token'

    ConfigService configService
    WebSocketService webSocketService
    AlertBannerService alertBannerService
    MonitorService monitorService
    TrackService trackService
    TrackLogAdminService trackLogAdminService

    private TimeZone _appTimeZone
    private List<Map> _libraries

    private CachedValue<Map> _inventory = createCachedValue(
        name: 'inventory',
        expireTime: { (inventoryConfig.cacheMins ?: 0) * MINUTES }
    )

    static clearCachesConfigs = ['xhAppTimeZone', 'xhInventoryConfig']


    /**
     * Official TimeZone for this application - e.g. the zone of the head office or trading center.
     *
     * Used to format or parse business related dates that need to be considered and displayed in a
     * consistent, agreed upon TimeZone, regardless of the location or zone of a client browser.
     * Not to be confused with `serverTimeZone` below.
     */
    TimeZone getAppTimeZone() {
        return _appTimeZone ?= calcAppTimeZone()
    }

    /** TimeZone of the server/JVM running this application. */
    TimeZone getServerTimeZone() {
        return Calendar.instance.timeZone
    }

    /** Full bundle of environment-related metadata, for serialization to JS clients. */
    Map getEnvironment() {
        def serverTz = serverTimeZone,
            appTz = appTimeZone,
            now = System.currentTimeMillis()

        def ret = [
                appCode:                Utils.appCode,
                appName:                Utils.appName,
                appVersion:             Utils.appVersion,
                appBuild:               Utils.appBuild,
                appEnvironment:         Utils.appEnvironment.toString(),
                grailsVersion:          GrailsUtil.grailsVersion,
                hoistCoreVersion:       hoistCoreVersion,
                javaVersion:            System.getProperty('java.version'),
                serverTimeZone:         serverTz.toZoneId().id,
                serverTimeZoneOffset:   serverTz.getOffset(now),
                appTimeZone:            appTz.toZoneId().id,
                appTimeZoneOffset:      appTz.getOffset(now),
                webSocketsEnabled:      webSocketService.enabled,
                instanceName:           clusterService.instanceName,
                alertBanner:            alertBannerService.alertBanner,
                pollConfig:             configService.getObject(EnvPollConfig),
        ]

        if (authUser.isHoistAdminReader) {
            def dataSource = Utils.dataSourceConfig
            ret.databaseConnectionString = dataSource.url
            ret.databaseUser = dataSource.username
            ret.databaseCreateMode = dataSource.dbCreate
        }

        return ret
    }

    /**
     * Report server version, instance identity, and any active alert banner to the client.
     * Designed for rapid polling - keep this minimal and highly optimized.
     */
    Map environmentPoll() {
        return [
            appCode     : Utils.appCode,
            appVersion  : Utils.appVersion,
            appBuild    : Utils.appBuild,
            instanceName: clusterService.instanceName,
            alertBanner : alertBannerService.alertBanner,
            pollConfig  : configService.getObject(EnvPollConfig)
        ]
    }

    //---------------------
    // Inventory
    //---------------------
    /**
     * Report describing this deployment - versions, libraries, runtime, cluster, database,
     * Hoist features in use, monitor status, and aggregate activity - for automated inventory
     * sweeps across apps. Each section is built independently: a section that fails reports an
     * `error` in place of its content rather than failing the report.
     *
     * Cached on each instance for `xhInventoryConfig.cacheMins`. Note this method does *not*
     * check access or `xhInventoryConfig.enabled` - see {@link #ensureInventoryAccess}. Apps may
     * call it directly, e.g. to push the report to a central collector.
     */
    Map getInventory() {
        _inventory.getOrCreate { buildInventory() }
    }

    /**
     * Throw unless the given request may read the inventory report.
     *
     * Throws `NotFoundException` when the report is disabled, so a disabled endpoint is
     * indistinguishable from a missing one. Otherwise allows authenticated users with
     * `HOIST_ADMIN_READER`, or any caller presenting the `xhInventoryToken` via the
     * {@link #INVENTORY_TOKEN_HEADER} or an `Authorization: Bearer` header. No token is required
     * if `xhInventoryConfig.requireToken` is false.
     */
    void ensureInventoryAccess(HttpServletRequest request) {
        def conf = inventoryConfig
        if (!conf.enabled) throw new NotFoundException()
        if (authUser?.isHoistAdminReader || !conf.requireToken) return

        def expected = configService.getPwdIfSet('xhInventoryToken')
        if (!expected) {
            logWarn('Rejected inventory request - xhInventoryToken required but not configured')
            throw new NotAuthenticatedException()
        }
        if (!tokensMatch(expected, getRequestToken(request))) {
            logWarn('Rejected inventory request - missing or invalid token', [remoteAddr: request.remoteAddr])
            throw new NotAuthenticatedException()
        }
    }

    /**
     * Runtime details of the local instance, gathered from every cluster instance for the
     * `cluster` section of the inventory report.
     * @internal
     */
    Map getLocalInventory() {
        def rt = Runtime.runtime,
            mb = 1024 * 1024
        return [
            name               : clusterService.instanceName,
            isPrimary          : clusterService.isPrimary,
            state              : ClusterService.instanceState.toString(),
            startupTime        : ClusterService.startupTime,
            uptimeMs           : ManagementFactory.runtimeMXBean.uptime,
            hostName           : hostName,
            pid                : ProcessHandle.current().pid(),
            availableProcessors: rt.availableProcessors(),
            heapUsedMb         : ((rt.totalMemory() - rt.freeMemory()) / mb).toLong(),
            heapMaxMb          : (rt.maxMemory() / mb).toLong(),
            webSocketChannels  : webSocketService.enabled ? webSocketService.localChannels.size() : null
        ]
    }

    InventoryConfig getInventoryConfig() {
        configService.getObject(InventoryConfig)
    }

    //---------------------
    // Implementation
    //---------------------
    private Map buildInventory() {
        withInfo('Building inventory report') {
            def conf = inventoryConfig,
                excluded = (conf.excludeSections ?: []) as Set
            Map ret = [
                inventoryVersion: INVENTORY_FORMAT_VERSION,
                generated       : new Date(),
                generatedBy     : clusterService.instanceName,
                tags            : conf.tags
            ]
            INVENTORY_SECTIONS.each { section ->
                if (!excluded.contains(section)) {
                    ret[section] = safely(section) { buildSection(section, conf) }
                }
            }
            return ret
        }
    }

    private Object buildSection(String section, InventoryConfig conf) {
        switch (section) {
            case 'app':          return appInventory
            case 'runtime':      return runtimeInventory
            case 'dependencies': return dependencyInventory
            case 'client':       return getClientInventory(conf.usageDays ?: 30)
            case 'cluster':      return clusterInventory
            case 'database':     return getDatabaseInventory(conf.includeDatabaseUrl)
            case 'features':     return featureInventory
            case 'monitors':     return monitorInventory
            case 'usage':        return trackLogAdminService.getUsageSummary(conf.usageDays ?: 30)
            case 'libraries':    return _libraries ?= LibraryInventory.scanClasspath()
            case 'custom':       return customInventory
            default: throw new IllegalArgumentException("Unknown inventory section '$section'")
        }
    }

    private Map getAppInventory() {
        return [
            appCode          : Utils.appCode,
            appName          : Utils.appName,
            appPackage       : Utils.appPackage,
            appVersion       : Utils.appVersion,
            appBuild         : Utils.appBuild,
            appEnvironment   : Utils.appEnvironment.toString(),
            isProduction     : Utils.isProduction,
            hoistCoreVersion : hoistCoreVersion,
            hoistReactVersion: Utils.hoistReactVersion
        ]
    }

    private Map getRuntimeInventory() {
        return [
            javaVersion     : System.getProperty('java.version'),
            javaVendor      : System.getProperty('java.vendor'),
            javaVmName      : System.getProperty('java.vm.name'),
            osName          : System.getProperty('os.name'),
            osVersion       : System.getProperty('os.version'),
            osArch          : System.getProperty('os.arch'),
            servletContainer: Holders.servletContext?.serverInfo,
            kubernetes      : System.getenv('KUBERNETES_SERVICE_HOST') != null,
            serverTimeZone  : serverTimeZone.toZoneId().id,
            appTimeZone     : appTimeZone.toZoneId().id
        ]
    }

    private Map getDependencyInventory() {
        return [
            java      : System.getProperty('java.version'),
            groovy    : GroovySystem.version,
            grails    : GrailsUtil.grailsVersion,
            hoistCore : hoistCoreVersion,
            hoistReact: Utils.hoistReactVersion,
            *         : LibraryInventory.frameworkVersions
        ]
    }

    /**
     * Client-side library versions, from two sources that apps may have either, both, or neither of:
     *  - `declared` - versions baked into the server build's metadata. Reflects what the client
     *    `package.json` declared, which may differ from what was resolved and deployed.
     *  - `builds` - client builds observed loading, with the library versions each reported at
     *    runtime. The source of truth for what users run, but requires a hoist-react version that
     *    reports `clientLibraries` with its app load tracking.
     */
    private Map getClientInventory(int days) {
        def builds = safely('client.builds') { trackLogAdminService.getClientBuildSummary(days) }
        return [
            declared          : [
                hoistReact  : Utils.hoistReactVersion,
                dependencies: declaredClientDependencies
            ],
            observedHoistReact: builds instanceof List ?
                builds.collect { it.libraries?.hoistReact }.findAll().unique() :
                null,
            builds            : builds
        ]
    }

    // Optional `info.xh.clientDependencies` build metadata - a JSON object of client package name
    // to version, for app builds that opt to supply it. Null if not supplied or invalid.
    private Map getDeclaredClientDependencies() {
        def raw = Metadata.current.getProperty('info.xh.clientDependencies', String, null)
        if (!raw) return null
        try {
            return JSONParser.parseObject(raw)
        } catch (Exception e) {
            logWarn('Ignoring invalid info.xh.clientDependencies build metadata', e.message)
            return null
        }
    }

    private Map getClusterInventory() {
        def instances = runOnAllInstances(this.&getLocalInventory).collect { name, result ->
            result.exception ? [name: name, error: result.exception.causeMessage] : result.value
        }
        return [
            clusterName         : ClusterService.clusterName,
            multiInstanceEnabled: ClusterService.multiInstanceEnabled,
            primaryName         : clusterService.primaryName,
            instanceCount       : instances.size(),
            instances           : instances
        ]
    }

    private Map getDatabaseInventory(boolean includeUrl) {
        def dsConfig = Utils.dataSourceConfig,
            ret = Utils.dataSource.connection.withCloseable { conn ->
                def md = conn.metaData
                [
                    productName   : md.databaseProductName,
                    productVersion: md.databaseProductVersion,
                    driverName    : md.driverName,
                    driverVersion : md.driverVersion
                ]
            }
        ret.dbCreate = dsConfig.dbCreate
        if (includeUrl) ret.url = dsConfig.url
        return ret
    }

    private Map getFeatureInventory() {
        def metricsConf = configService.getObject(MetricsConfig)
        return [
            authenticationService: Utils.authenticationService.class.name,
            userService          : Utils.userService.class.name,
            roleService          : Utils.roleService.class.name,
            ldap                 : Utils.ldapService.enabled,
            entraId              : Utils.entraIdService.enabled,
            impersonation        : configService.getBool('xhEnableImpersonation'),
            activityTracking     : trackService.enabled,
            monitoring           : configService.getBool('xhEnableMonitoring'),
            webSockets           : webSocketService.enabled,
            tracing              : Utils.traceService.enabled,
            metricsPrometheus    : metricsConf.prometheusEnabled,
            metricsOtlp          : metricsConf.otlpEnabled,
            alertBanner          : configService.getObject(AlertBannerConfig).enabled,
            emailOverride        : configService.getStringIfSet('xhEmailOverride') != null
        ]
    }

    private Map getMonitorInventory() {
        def results = monitorService.results,
            active = results.findAll { it.status != INACTIVE }
        return [
            enabled      : configService.getBool('xhEnableMonitoring'),
            overallStatus: (active ? active.max { it.status.severity }.status : INACTIVE).toString(),
            statusCounts : results.countBy { it.status.toString() },
            issues       : results.findAll { it.status in [WARN, FAIL] }.collect {
                [
                    code             : it.monitor.code,
                    name             : it.name,
                    status           : it.status.toString(),
                    message          : it.message,
                    lastStatusChanged: it.lastStatusChanged
                ]
            }
        ]
    }

    private Map getCustomInventory() {
        Utils.appContext.getBeansOfType(InventoryContributor).collectEntries { name, contributor ->
            [name, safely("custom.$name") { contributor.inventoryData }]
        }
    }

    private Object safely(String section, Closure c) {
        try {
            return c()
        } catch (Throwable e) {
            logError("Failed to build inventory section '$section'", e)
            return [error: e.message ?: e.class.simpleName]
        }
    }

    private static String getHostName() {
        try {
            return System.getenv('HOSTNAME') ?: InetAddress.localHost.hostName
        } catch (Exception ignored) {
            return null
        }
    }

    private static String getRequestToken(HttpServletRequest request) {
        def header = request.getHeader(INVENTORY_TOKEN_HEADER)
        if (header) return header.trim()

        def auth = request.getHeader('Authorization')
        return auth?.regionMatches(true, 0, 'Bearer ', 0, 7) ? auth.substring(7).trim() : null
    }

    // Compare digests rather than raw values - constant-time, and does not leak token length.
    private static boolean tokensMatch(String expected, String provided) {
        if (!provided) return false
        def sha = { String s -> MessageDigest.getInstance('SHA-256').digest(s.getBytes(UTF_8)) }
        return MessageDigest.isEqual(sha(expected), sha(provided))
    }

    private TimeZone calcAppTimeZone() {
        def defaultZone = 'UTC',
            configZoneId = configService.getString('xhAppTimeZone')

        if (!TimeZone.availableIDs.contains(configZoneId)) {
            log.error("Invalid xhAppTimeZone config: '$configZoneId' not a valid ZoneId - will fall back to $defaultZone.")
            configZoneId = defaultZone;
        }

        return TimeZone.getTimeZone(configZoneId)
    }


    void clearCaches() {
        _appTimeZone = null
        _inventory.clear()
        super.clearCaches()
    }

    Map getAdminStats() {
        [config: configForAdminStats('xhAppTimeZone', 'xhInventoryConfig')]
    }
}
