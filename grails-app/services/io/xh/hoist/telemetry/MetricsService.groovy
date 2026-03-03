/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry

import groovy.transform.CompileStatic
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.micrometer.core.instrument.Clock
import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService

import java.rmi.registry.Registry

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
 * export registries (e.g. Datadog) can be added via {@code publishRegistry.add()} in
 * application bootstrap code.
 *
 * The namespace prefix defaults to the application code and can be overridden via the
 * {@code namespace} key in {@code xhMetricsConfig}. Note that the namespace is applied
 * at service initialization and cannot be changed at runtime — a restart is required.
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
     * ({@code application}, {@code instance}). A {@code source} tag also classifies
     * each metric's origin — 'app' (default), 'hoist', or 'infra' are built-in sources, and
     * 'app' will be provided as the default.
     *
     * Metrics with {@code source=app} will have their names prefixed with the application
     * namespace.
     */
    final CompositeMeterRegistry registry = new CompositeMeterRegistry()

    /**
     * Composite registry for all export sinks (Prometheus, OTLP, etc.).
     *
     * Applications can add additional export registries here via {@code publishRegistry.add()}.
     * Only metrics listed in {@code xhMetricsPublished} are admitted to this registry.
     */
    final CompositeMeterRegistry publishRegistry = new CompositeMeterRegistry()

    /**
     * An in-memory registry for *reading* the current metric values on this
     * instance.
     *
     * Not typically used by applications. To register a metric, use the `registry`
     * property instead. To register a publishing sink, use `publishRegistry`.
     */
    final SimpleMeterRegistry readOnlyRegistry = new SimpleMeterRegistry()

    ConfigService configService

    private PrometheusMeterRegistry _prometheusRegistry
    private OtlpMeterRegistry _otlpRegistry

    void init() {
        registry.add(readOnlyRegistry)
        registry.add(publishRegistry)
        applyFilters()
        syncPublishRegistries()
        bindJvmMetrics()
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

    /** List of metric names published to export sinks. */
    List<String> getPublishedMetrics() {
        configService.getList('xhMetricsPublished', [])
    }

    /**
     * Add or remove metric names from the published list.
     * Culls any names that no longer correspond to registered metrics.
     */
    void updatePublishedMetrics(List<String> names, boolean published) {
        def current = publishedMetrics,
            updated = published ? (current + names).unique() : current - names,
            knownNames = readOnlyRegistry.meters.collect { it.id.name } as Set
        configService.setValue('xhMetricsPublished', updated.findAll { knownNames.contains(it) })
    }

    /**
     * Scrape this instance.
     */
    private String prometheusScrape() {
        if (!_prometheusRegistry) {
            throw new RuntimeException('Prometheus not enabled')
        }
        _prometheusRegistry.scrape()
    }

    //------------------------
    // Implementation
    //------------------------
    private void applyFilters() {
        // Deny cluster-scoped metrics on non-primary instances
        registry.config().meterFilter(new MeterFilter() {
            MeterFilterReply accept(Meter.Id id) {
                if (!clusterService.isPrimary && id.tags.any { it.key == 'instance' && it.value == 'cluster' }) {
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

        // Limit published metrics to those listed in xhMetricsPublished
        publishRegistry.config().meterFilter(new MeterFilter() {
            MeterFilterReply accept(Meter.Id id) {
                def names = publishedMetrics as Set
                !names || names.contains(id.name) ? MeterFilterReply.NEUTRAL : MeterFilterReply.DENY
            }
        })
    }

    private void bindJvmMetrics() {
        def tags = Tags.of('source', 'infra')
        new ClassLoaderMetrics(tags).bindTo(registry)
        new JvmMemoryMetrics(tags).bindTo(registry)
        new JvmGcMetrics(tags).bindTo(registry)
        new JvmThreadMetrics(tags).bindTo(registry)
        new ProcessorMetrics(tags).bindTo(registry)
    }

    private void syncPublishRegistries() {
        withDebug(['Syncing publish registries', [prometheus: config.prometheusEnabled, otlp: config.otlpEnabled]]) {

            // Prometheus
            if (_prometheusRegistry) {
                publishRegistry.remove(_prometheusRegistry)
                _prometheusRegistry.close()
                _prometheusRegistry = null
            }
            if (config.prometheusEnabled) {
                def conf = prefixKeys('prometheus', config.prometheusConfig)
                _prometheusRegistry = new PrometheusMeterRegistry(conf::get as PrometheusConfig)
                publishRegistry.add(_prometheusRegistry)
            }

            // OTLP
            if (_otlpRegistry) {
                publishRegistry.remove(_otlpRegistry)
                _otlpRegistry.stop()
                _otlpRegistry.close()
                _otlpRegistry = null
            }
            if (config.otlpEnabled) {
                def conf = prefixKeys('otlp', config.otlpConfig)
                _otlpRegistry = new OtlpMeterRegistry(conf::get as OtlpConfig, Clock.SYSTEM)
                publishRegistry.add(_otlpRegistry)
            }

            // Clear and re-seat to force all meters through the publish filter again — ensures
            // publishing changes apply to *all* sinks, including  app-added
            registry.remove(publishRegistry)
            publishRegistry.clear()
            registry.add(publishRegistry)
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
        syncPublishRegistries()
    }

    Map getAdminStats() { [
        config: configForAdminStats('xhMetricsConfig')
    ] }
}