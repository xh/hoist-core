/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.observe

import groovy.transform.CompileDynamic
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.config.MeterFilterReply
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.micrometer.core.instrument.Clock
import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService

import static io.xh.hoist.cluster.ClusterService.instanceName
import static io.xh.hoist.util.ClusterUtils.runOnAllInstances
import static io.xh.hoist.util.Utils.appCode

/**
 * Central service for Micrometer metrics in a Hoist application.
 *
 * Exposes a {@link CompositeMeterRegistry} via {@link #registry} for meter registration.
 * All meters registered through this registry automatically receive a configurable namespace
 * prefix and default tags ({@code application}, {@code instance}).
 *
 * Built-in support for Prometheus (pull-based) and OTLP (push-based) export registries,
 * configured dynamically via the {@code xhMetricsConfig} soft config entry. Additional
 * export registries (e.g. Datadog) can be added via {@code registry.add()} in application
 * bootstrap code.
 *
 * The namespace prefix defaults to the application code and can be overridden via the
 * {@code namespace} key in {@code xhMetricsConfig}. Note that the namespace is applied
 * at service initialization and cannot be changed at runtime — a restart is required.
 * Other config values (e.g. export registry settings) are dynamic and take effect
 * immediately when {@code xhMetricsConfig} is updated.
 */
@CompileDynamic
class MetricsService extends BaseService {

    /**
     * Tag value for the {@code instance} label on cluster-scoped metrics.
     * Metrics tagged with this value may only be registered on the primary instance.
     * The MeterFilter will reject (deny) any such metric on non-primary nodes,
     * ensuring cluster-scoped metrics appear exactly once in aggregated scrapes.
     */
    static final String CLUSTER_TAG = 'cluster'

    /**
     * Main entry point for meter registration.
     *
     * All meters registered through this registry automatically receive default tags
     * ({@code application}, {@code instance}. A {@code source} tag will also classifies
     * each metric's origin — 'app' (default), 'hoist', or 'infra' are built-in sources, and
     * 'app' will be provided as the default. Applications and plug-ins may choose to add
     * additional sources.
     *
     * Metrics with {@code source=app} have will their names prefixed with the application
     * namespace.
     */
    CompositeMeterRegistry registry


    static clearCachesConfigs = ['xhMetricsConfig']

    ConfigService configService
    private PrometheusMeterRegistry _prometheusRegistry
    private OtlpMeterRegistry _otlpRegistry

    void init() {
        registry = new CompositeMeterRegistry()

        // Deny cluster-scoped metrics on non-primary instances
        registry.config().meterFilter(new MeterFilter() {
            MeterFilterReply accept(Meter.Id id) {
                if (!clusterService.isPrimary && id.tags.any { it.key == 'instance' && it.value == CLUSTER_TAG }) {
                    logError("Cluster-scoped metric registered on non-primary instance", id.name)
                    return MeterFilterReply.DENY
                }
                MeterFilterReply.NEUTRAL
            }
        })

        // Apply namespace prefix and default tags
        registry.config().meterFilter(new MeterFilter() {
            Meter.Id map(Meter.Id id) {
                def source = id.getTag('source') ?: 'app';
                if (source == 'app') {
                    id = id.withName("${namespace}.${id.name}")
                } else if (source == 'hoist') {
                    id = id.withName("hoist.${id.name}")
                }

                [application: appCode, instance: instanceName, source: source].each { k, v ->
                    if (!id.tags.any { it.key == k }) {
                        id = id.withTags(Tags.of(k, v))
                    }
                }
                id
            }
        })

        bindJvmMetrics()
        syncBuiltInRegistries()
    }


    /**
     * Return Prometheus exposition data aggregated across all cluster instances.
     *
     * Any instance can service this request — it fans out to all instances via
     * Hazelcast, collects each instance's scrape output, and concatenates the
     * results. Each metric already carries an {@code instance} tag distinguishing
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

    /** Scrape this instance, excluding cluster-scoped metrics from non-primary nodes. */
    private String prometheusScrape() {
        if (!_prometheusRegistry) {
            throw new RuntimeException('Prometheus not enabled')
        }

        if (clusterService.isPrimary) return _prometheusRegistry.scrape()

        // Non-primary: scrape only instance-scoped metrics
        def includedNames = registry.meters
            .findAll { it.id.tags.every { tag -> tag.key != 'instance' || tag.value != CLUSTER_TAG } }
            .collect { it.id.name } as Set
        _prometheusRegistry.scrape('text/plain', includedNames)
    }


    //------------------------
    // Implementation
    //------------------------
    private void bindJvmMetrics() {
        def tags = Tags.of('source', 'infra')
        new ClassLoaderMetrics(tags).bindTo(registry)
        new JvmMemoryMetrics(tags).bindTo(registry)
        new JvmGcMetrics(tags).bindTo(registry)
        new JvmThreadMetrics(tags).bindTo(registry)
        new ProcessorMetrics(tags).bindTo(registry)
    }

    private void syncBuiltInRegistries() {
        withDebug(['Syncing registries', [prometheus: config.prometheusEnabled, otlp: config.otlpEnabled]]) {
            // Prometheus
            if (_prometheusRegistry) {
                registry.remove(_prometheusRegistry)
                _prometheusRegistry.close()
                _prometheusRegistry = null
            }
            if (config.prometheusEnabled) {
                def conf = prefixKeys('prometheus', config.prometheusConfig)
                _prometheusRegistry = new PrometheusMeterRegistry(conf::get as PrometheusConfig)
                registry.add(_prometheusRegistry)
            }

            // OTLP
            if (_otlpRegistry) {
                registry.remove(_otlpRegistry)
                _otlpRegistry.stop()
                _otlpRegistry.close()
                _otlpRegistry = null
            }
            if (config.otlpEnabled) {
                def conf = prefixKeys('otlp', config.otlpConfig)
                _otlpRegistry = new OtlpMeterRegistry(conf::get as OtlpConfig, Clock.SYSTEM)
                registry.add(_otlpRegistry)
            }
        }
    }

    private static Map<String, String> prefixKeys(String prefix, Map config) {
        (config ?: [:]).collectEntries { k, v -> ["${prefix}.${k}".toString(), v?.toString()] }
    }

    private String getNamespace() {
        config.namespace ?: appCode
    }

    private MetricsConfig getConfig() {
        new MetricsConfig(configService.getMap('xhMetricsConfig'))
    }


    void clearCaches() {
        super.clearCaches()
        syncBuiltInRegistries()
    }

    Map getAdminStats() { [
        config: configForAdminStats('xhMetricsConfig')
    ] }
}