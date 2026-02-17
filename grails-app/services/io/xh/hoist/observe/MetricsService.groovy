/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.observe

import groovy.transform.CompileDynamic
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.micrometer.core.instrument.Clock
import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService

import static io.xh.hoist.cluster.ClusterService.instanceName
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
     * Main entry point for applications.  Register micrometer Meters, Timers
     * with this object using the built-in 'register' method on builders.
     *
     * Additional export registries may also be added via 'registry.add'.
     */
    CompositeMeterRegistry registry

    static clearCachesConfigs = ['xhMetricsConfig']

    ConfigService configService
    private PrometheusMeterRegistry _prometheusRegistry
    private OtlpMeterRegistry _otlpRegistry

    void init() {
        registry = new CompositeMeterRegistry()

        // Apply default namespace prefix and tags
        registry.config().meterFilter(new MeterFilter() {
            Meter.Id map(Meter.Id id) {
                def ret = id.withName("${namespace}.${id.name}")

                [application: appCode, instance: instanceName].each {key, value ->
                    if (!id.tags.any { it.key == key }) {
                        ret = ret.withTags(Tags.of(key, value))
                    }
                }
                ret
            }
        })

        syncBuiltInRegistries()
    }


    /**
     * Return metrics data for Prometheus endpoint.
     *
     * Applications providing Prometheus support should expose the value
     * returned by this method in a dedicated endpoint in lieu of the built-in
     * 'actuator/prometheus'.
     *
     * This method returns the full cluster metrics, from the primary server
     * regardless of the instance servicing the request.
     */
    String prometheusData() {
        //def result = runOnPrimary(this.&prometheusScrape)
        //if (result.exception) throw result.exception
        //return result.value
        prometheusScrape()
    }


    //------------------------
    // Implementation
    //------------------------
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

    private String prometheusScrape() {
        if (!_prometheusRegistry) {
            throw new RuntimeException('Prometheus not enabled')
        }
        _prometheusRegistry.scrape()
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