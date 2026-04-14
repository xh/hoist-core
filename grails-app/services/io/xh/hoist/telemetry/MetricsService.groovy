/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry

import groovy.transform.CompileStatic
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.config.MeterFilterReply
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.micrometer.core.instrument.Clock
import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService

import static io.micrometer.core.instrument.config.MeterFilterReply.DENY
import static io.micrometer.core.instrument.config.MeterFilterReply.NEUTRAL
import static io.xh.hoist.cluster.ClusterService.instanceName
import static io.xh.hoist.cluster.ClusterService.otelResourceAttributes
import static io.xh.hoist.util.ClusterUtils.runOnAllInstances
import static io.xh.hoist.util.Utils.appCode

/**
 * Central service for Micrometer metrics in a Hoist application.
 *
 * Exposes a {@link CompositeMeterRegistry} via {@link #registry} for meter registration.
 * All meters registered through this registry automatically receive default tags
 * ({@code xh.application}, {@code xh.instance}, {@code xh.source}).
 *
 * Built-in support for Prometheus (pull-based) and OTLP (push-based) export registries,
 * configured dynamically via the {@code xhMetricsConfig} soft config entry. Additional
 * export registries (e.g. Datadog) can be added via {@link #addPublishRegistry}.
 *
 * Other config values (e.g. export registry settings) are dynamic and take effect
 * immediately when {@code xhMetricsConfig} is updated.
 */
@CompileStatic
class MetricsService extends BaseService {

    static clearCachesConfigs = ['xhMetricsConfig', 'xhMetricsPublished']

    /**
     * Main entry point for meter registration.
     *
     * All meters registered through this registry automatically receive default tags
     * ({@code xh.application}, {@code xh.instance}). A {@code xh.source} tag also classifies
     * each metric's origin — 'app' (default) or 'hoist' are built-in sources, and
     * 'app' will be provided as the default.
     */
    final CompositeMeterRegistry registry = new CompositeMeterRegistry()

    /**
     * An in-memory registry for *reading* the current metric values on this
     * instance.
     *
     * Not typically used by applications. To register a metric, use the `registry`
     * property instead. To register a publishing sink, use {@link #addPublishRegistry}.
     */
    final SimpleMeterRegistry readOnlyRegistry = new SimpleMeterRegistry()

    ConfigService configService

    private List<MeterRegistry> _publishRegistries = []
    private PrometheusMeterRegistry _prometheusRegistry
    private OtlpMeterRegistry _otlpRegistry

    void init() {
        registry.add(readOnlyRegistry)
        applyFilters()
        syncConfig()
    }


    /**
     * Return Prometheus exposition data aggregated across all cluster instances.
     *
     * Any instance can service this request — it fans out to all instances via
     * Hazelcast, collects each instance's scrape output, and concatenates the
     * results. Each metric already carries a {@code xh.instance} tag distinguishing
     * its source.
     *
     * Applications should expose the value returned by this method in a dedicated
     * endpoint in lieu of the built-in 'actuator/prometheus'.
     */
    String prometheusData() {
        def results = runOnAllInstances(this.&prometheusScrape)
        results.values()
            .findAll { !it.exception }
            .collect { it.value }
            .join('\n')
    }


    /** List of metric names published to export sinks. */
    List<String> getPublishedMetrics() {
        configService.getList('xhMetricsPublished', [])
    }

    /**
     * Register an additional {@link MeterRegistry} to receive published metrics.
     * A publish filter will be applied to the registry. Triggers a rebuild.
     */
    synchronized void addPublishRegistry(MeterRegistry reg) {
        reg.config().meterFilter(publishFilter)
        _publishRegistries = _publishRegistries + [reg]
        registry.add(reg)
    }

    /** Remove a previously registered publish {@link MeterRegistry}. */
    synchronized void removePublishRegistry(MeterRegistry reg) {
        _publishRegistries = _publishRegistries - [reg]
        registry.remove(reg)
    }

    /**
     * Add or remove metric names from the published list.
     */
    void updatePublishedMetrics(List<String> names, boolean published) {
        def current = publishedMetrics,
            updated = published ? (current + names).unique() : current - names
        configService.setValue('xhMetricsPublished', updated)
    }

    /**
     * Scrape this instance.
     */
    private String prometheusScrape() {
        _prometheusRegistry?.scrape() ?: ''
    }

    //------------------------
    // Implementation
    //------------------------
    private void applyFilters() {
        // Deny cluster-scoped metrics on non-primary instances
        registry.config().meterFilter(new MeterFilter() {
            MeterFilterReply accept(Meter.Id id) {
                if (!clusterService.isPrimary && id.getTag('xh.instance') == 'cluster') {
                    logError("Cluster-scoped metric registered on non-primary instance", id.name)
                    return DENY
                }
                NEUTRAL
            }
        })

        registry.config().meterFilter(new MeterFilter() {
            Meter.Id map(Meter.Id id) {
                // default source
                def name = id.name,
                    source = id.getTag('xh.source')
                if (!source) {
                    source = isDefaultHoistSource(name) ? 'hoist' : 'app'
                }

                // apply default tags (including source) if not present
                ['xh.application': appCode, 'xh.instance': instanceName, 'xh.source': source].each { k, v ->
                    if (!id.getTag(k)) {
                        id = id.withTags(Tags.of(k, v))
                    }
                }
                id
            }
        })
    }

    /** Publish filter applied to each export registry individually. */
    private final MeterFilter publishFilter = new MeterFilter() {
        MeterFilterReply accept(Meter.Id id) {
            publishedMetrics.contains(id.name) ? NEUTRAL : DENY
        }
    }

    private static boolean isDefaultHoistSource(String name) {
        ['hoist.', 'jdbc.', 'jvm.', 'system.', 'process.', 'disk.', 'logback.', 'tomcat.']
            .any { name.startsWith(it) }
    }

    private synchronized void syncConfig() {
        withDebug(['Syncing config', [prometheus: config.prometheusEnabled, otlp: config.otlpEnabled]]) {

            // Remove all publish registries from main registry
            _publishRegistries.each { registry.remove(it) }
            def regs = new ArrayList<MeterRegistry>(_publishRegistries)

            // Tear down and rebuild built-in export registries
            if (_prometheusRegistry) {
                regs.remove(_prometheusRegistry)
                _prometheusRegistry.close()
                _prometheusRegistry = null
            }
            if (config.prometheusEnabled) {
                def conf = prefixKeys('prometheus', config.prometheusConfig)
                _prometheusRegistry = new PrometheusMeterRegistry({conf[it]} as PrometheusConfig)
                _prometheusRegistry.config().meterFilter(publishFilter)
                regs.add(_prometheusRegistry)
            }

            if (_otlpRegistry) {
                regs.remove(_otlpRegistry)
                _otlpRegistry.stop()
                _otlpRegistry.close()
                _otlpRegistry = null
            }
            if (config.otlpEnabled) {
                def otlpConf = config.otlpConfig ?: [:]
                otlpConf.resourceAttributes = otelResourceAttributes.collect { k, v -> "${k}=${v}" }.join(',')
                def conf = prefixKeys('otlp', otlpConf)
                _otlpRegistry = new OtlpMeterRegistry({conf[it]} as OtlpConfig, Clock.SYSTEM)
                _otlpRegistry.config().meterFilter(publishFilter)
                regs.add(_otlpRegistry)
            }

            // Publish the new list and re-add all publish registries
            _publishRegistries = regs
            _publishRegistries.each { registry.add(it) }
        }
    }

    private static Map<String, String> prefixKeys(String prefix, Map<String, String> config) {
        (config ?: [:]).collectEntries { k, v -> ["${prefix}.${k}".toString(), v?.toString()] }
    }

    private MetricsConfig getConfig() {
        new MetricsConfig(configService.getMap('xhMetricsConfig'))
    }


    void clearCaches() {
        super.clearCaches()
        syncConfig()
    }

    Map getAdminStats() {
        def regs = _publishRegistries
        [
            config: configForAdminStats('xhMetricsConfig'),
            metrics: readOnlyRegistry.meters.size(),
            publishedMetrics: regs ? regs.first().meters.size() : 0
        ]
    }
}
