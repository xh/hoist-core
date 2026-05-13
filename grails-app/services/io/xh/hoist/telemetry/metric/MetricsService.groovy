/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry.metric

import groovy.transform.CompileStatic
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.ToDoubleFunction
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
import static io.xh.hoist.telemetry.OtelUtils.getOtlpEnabledInLocalDev
import static io.xh.hoist.cluster.ClusterService.otelResourceAttributes
import static io.xh.hoist.util.ClusterUtils.runOnAllInstances
import static io.xh.hoist.util.Utils.appCode
import static io.xh.hoist.util.Utils.isLocalDevelopment

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

    private final Map<String, TimerSpec> _timerSpecs = new ConcurrentHashMap<>()
    private final Map<String, CounterSpec> _counterSpecs = new ConcurrentHashMap<>()

    void init() {
        registry.add(readOnlyRegistry)
        applyFilters()
        syncConfig()
    }

    /**
     * Configure a timer name with default tags, distribution statistics and metadata.
     *
     * Call once at app init — typically from a service `init()` method. Configuration is keyed
     * by metric name and applies to all tagged variants of that name created later by
     * {@link #recordTimer} or {@link io.xh.hoist.telemetry.ObservedRun#timer}.
     *
     * This method does not register an actual Timer. To create and register a Timer with a
     * concrete and fixed set of tags use {@link #registerTimer} instead.
     *
     * Distribution config (percentiles, slos, etc.) is applied via a {@link MeterFilter} and
     * must be set before the affected meters are first recorded — otherwise the meter is
     * created with the unfiltered config and this call has no effect for that instance.
     *
     * @param name              metric name (must match the name used at the call site)
     * @param description       human-readable description shown in the admin metrics view
     * @param tags              default tags applied to every recorded variant of `name` —
     *                          merged in at meter registration if not already present on the
     *                          call site's tag set
     * @param percentiles       client-side percentiles to compute (e.g. [0.5, 0.95, 0.99])
     * @param slos              service-level-objective buckets for histogram output
     * @param publishHistogram  publish a percentile histogram for server-side aggregation
     *                          (e.g. Prometheus histogram_quantile)
     * @param minExpected       minimum expected value - used to size the histogram
     * @param maxExpected       maximum expected value - used to size the histogram
     * @param owner             optional BaseService creating this metric — used for tagging
     *                          and naming the metric.
     * @param useNamePrefix     when true (the default), the owner's name prefix is applied.
     */
    @NamedVariant
    void configureTimer(
        @NamedParam(required = true) String name,
        @NamedParam String description = null,
        @NamedParam Map<String, String> tags = null,
        @NamedParam List<Double> percentiles = null,
        @NamedParam List<Duration> slos = null,
        @NamedParam boolean publishHistogram = false,
        @NamedParam Duration minExpected = null,
        @NamedParam Duration maxExpected = null,
        @NamedParam BaseService owner = null,
        @NamedParam boolean useNamePrefix = true
    ) {
        name = applyOwnerPrefix(name, owner, useNamePrefix)
        tags = applyOwnerTag(tags, owner)
        _timerSpecs[name] = new TimerSpec(
            name: name,
            description: description,
            tags: tags,
            percentiles: percentiles,
            slos: slos,
            publishHistogram: publishHistogram,
            minExpected: minExpected,
            maxExpected: maxExpected
        )
    }

    /**
     * Create and register a concrete Micrometer {@link Timer} with a fixed set of tags.
     *
     * Distribution config (percentiles, SLOs, histogram, min/max expected) is intentionally
     * *not* a parameter here — set those once for the metric name via {@link #configureTimer}
     * and they will be applied to this Timer (and any other variants of the same name) via the
     * meter filter pipeline.
     */
    @NamedVariant
    Timer registerTimer(
        @NamedParam(required = true) String name,
        @NamedParam String description = null,
        @NamedParam Map<String, String> tags = null,
        @NamedParam BaseService owner = null,
        @NamedParam boolean useNamePrefix = true
    ) {
        name = applyOwnerPrefix(name, owner, useNamePrefix)
        tags = applyOwnerTag(tags, owner)
        Timer.builder(name)
            .description(description)
            .tags(toTags(tags))
            .register(registry)
    }

    /**
     * Configure a counter name with default tags and metadata.
     *
     * Call once at app init — typically from a service `init()` method. Configuration is keyed
     * by metric name and applies to all tagged variants of that name created later by
     * {@link #recordCount} or {@link io.xh.hoist.telemetry.ObservedRun#counter}.
     *
     * This method does not register an actual Counter. Description is stored in a side-map and
     * surfaced through the admin metrics view.
     *
     * @param name              metric name (must match the name used at the call site)
     * @param description       human-readable description shown in the admin metrics view
     * @param tags              default tags applied to every recorded variant of `name` —
     *                          merged in at meter registration if not already present on the
     *                          call site's tag set
     * @param owner             optional BaseService creating this metric — used for tagging
     *                          and naming the metric.
     * @param useNamePrefix     when true (the default), the owner's name prefix is applied.
     */
    @NamedVariant
    void configureCounter(
        @NamedParam(required = true) String name,
        @NamedParam String description = null,
        @NamedParam Map<String, String> tags = null,
        @NamedParam BaseService owner = null,
        @NamedParam boolean useNamePrefix = true
    ) {
        name = applyOwnerPrefix(name, owner, useNamePrefix)
        tags = applyOwnerTag(tags, owner)
        _counterSpecs[name] = new CounterSpec(name: name, description: description, tags: tags)
    }

    /**
     * Create and register a concrete Micrometer {@link Counter} with a fixed set of tags.
     */
    @NamedVariant
    Counter registerCounter(
        @NamedParam(required = true) String name,
        @NamedParam String description = null,
        @NamedParam Map<String, String> tags = null,
        @NamedParam BaseService owner = null,
        @NamedParam boolean useNamePrefix = true
    ) {
        name = applyOwnerPrefix(name, owner, useNamePrefix)
        tags = applyOwnerTag(tags, owner)
        Counter.builder(name)
            .description(description)
            .tags(toTags(tags))
            .register(registry)
    }

    /**
     * Create and register a {@link Gauge} that reads its current value from `valueFn` on demand.
     *
     * Unlike Timers and Counters — whose tagged variants are auto-created on first record —
     * a Gauge must be explicitly registered, as it needs a value-producing closure.
     *
     * @param name              metric name
     * @param valueFn           closure returning the current value. May return any
     *                          {@link Number} including null — null is surfaced as `NaN`, which
     *                          causes the gauge to skip emission rather than report a misleading
     *                          `0`.
     * @param description       human-readable description shown in the admin metrics view
     * @param tags              tags applied to this Gauge — merged with the standard default
     *                          tags applied by the registry
     * @param baseUnit          optional base unit for the gauge (e.g. 'bytes', 'milliseconds')
     * @param owner             optional BaseService creating this metric — used for tagging
     *                          and naming the metric.
     * @param useNamePrefix     when true (the default), the owner's name prefix is applied.
     */
    @NamedVariant
    Gauge registerGauge(
        @NamedParam(required = true) String name,
        @NamedParam(required = true) Closure<? extends Number> valueFn,
        @NamedParam String description = null,
        @NamedParam Map<String, String> tags = null,
        @NamedParam String baseUnit = null,
        @NamedParam BaseService owner = null,
        @NamedParam boolean useNamePrefix = true
    ) {
        name = applyOwnerPrefix(name, owner, useNamePrefix)
        tags = applyOwnerTag(tags, owner)
        Gauge.builder(name, owner ?: this, { def v = valueFn.call(); v == null ? Double.NaN : v.doubleValue() } as ToDoubleFunction)
            .description(description)
            .tags(toTags(tags))
            .baseUnit(baseUnit)
            .register(registry)
    }


    /**
     * Create and register a {@link FunctionCounter} that reads a monotonically-increasing total
     * from `countFn` on demand.
     *
     * Like {@link #registerGauge}, a FunctionCounter must be explicitly registered with a
     * value-producing closure — unlike a regular Counter whose tagged variants are auto-created
     * on first record.
     *
     * @param name              metric name
     * @param countFn           closure returning the current total. Null returns are treated as
     *                          `0` to preserve the monotonic-counter contract.
     * @param description       human-readable description shown in the admin metrics view
     * @param tags              tags applied to this FunctionCounter — merged with the standard
     *                          default tags applied by the registry
     * @param baseUnit          optional base unit for the counter (e.g. 'bytes')
     * @param owner             optional BaseService creating this metric — used for tagging
     *                          and naming the metric.
     * @param useNamePrefix     when true (the default), the owner's name prefix is applied.
     */
    @NamedVariant
    FunctionCounter registerFunctionCounter(
        @NamedParam(required = true) String name,
        @NamedParam(required = true) Closure<? extends Number> countFn,
        @NamedParam String description = null,
        @NamedParam Map<String, String> tags = null,
        @NamedParam String baseUnit = null,
        @NamedParam BaseService owner = null,
        @NamedParam boolean useNamePrefix = true
    ) {
        name = applyOwnerPrefix(name, owner, useNamePrefix)
        tags = applyOwnerTag(tags, owner)
        FunctionCounter.builder(name, owner ?: this, { countFn.call()?.doubleValue() ?: 0d } as ToDoubleFunction)
            .description(description)
            .tags(toTags(tags))
            .baseUnit(baseUnit)
            .register(registry)
    }

    /** Lookup the description for a named Timer or Counter, or null if none configured. */
    String getMeterDescription(String name) {
        _timerSpecs[name]?.description ?: _counterSpecs[name]?.description
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

    /**
     * Record an elapsed time for a named timer. Auto-creates the timer on first use.
     * Tags are applied as-is; the standard default tags are added by the registry.
     *
     * The named timer should typically be configured ahead of time via {@link #configureTimer}.
     */
    @NamedVariant
    void recordTimer(
        @NamedParam(required = true) String name,
        @NamedParam(required = true) double valueMs,
        @NamedParam Map<String, String> tags = [:]
    ) {
        registry.timer(name, toTags(tags)).record((long)valueMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Increment a named counter by `value`. Auto-creates the counter on first use.
     * Tags are applied as-is; the standard default tags are added by the registry.
     *
     * The named counter should typically be configured ahead of time via {@link #configureCounter}.
     */
    @NamedVariant
    void recordCount(
        @NamedParam(required = true) String name,
        @NamedParam(required = true) double value,
        @NamedParam Map<String, String> tags = [:]
    ) {
        registry.counter(name, toTags(tags)).increment(value)
    }

    /**
     * Record a batch of metric entries, each a Map with `type` ('timer' | 'count'),
     * `name`, `value`, and optional `tags`. Used by the client-side metrics endpoint.
     */
    void submitClientMetrics(List<Map> entries) {
        entries.each { Map e ->
            def name = e.name as String,
                value = e.value as Number,
                tags = (e.tags ?: [:]) as Map<String, String>
            if (!name || value == null) {
                logWarn('Skipping invalid metric entry', e)
                return
            }

            switch (e.type) {
                case 'timer': recordTimer(name, value.doubleValue(), tags); break
                case 'count': recordCount(name, value.doubleValue(), tags); break
                default: logWarn('Unknown metric type', e)
            }
        }
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
        // Apply per-name distribution config from configureTimer() specs
        registry.config().meterFilter(new MeterFilter() {
            DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                def spec = _timerSpecs[id.name]
                if (!spec) return config
                def b = DistributionStatisticConfig.builder()
                if (spec.percentiles) b.percentiles(spec.percentiles as double[])
                if (spec.slos) b.serviceLevelObjectives(spec.slos.collect { it.toNanos() as double } as double[])
                if (spec.publishHistogram) b.percentilesHistogram(true)
                if (spec.minExpected != null) b.minimumExpectedValue(spec.minExpected.toNanos() as Double)
                if (spec.maxExpected != null) b.maximumExpectedValue(spec.maxExpected.toNanos() as Double)
                b.build().merge(config)
            }
        })

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

                // apply spec-defined default tags if not present
                TimerSpec ts = _timerSpecs[name]
                CounterSpec cs = _counterSpecs[name]
                Map<String, String> specTags = ts?.tags ?: cs?.tags
                specTags?.each { String k, String v ->
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
        ['xh.', 'jdbc.', 'jvm.', 'system.', 'process.', 'disk.', 'logback.', 'tomcat.']
            .any { name.startsWith(it) }
    }

    private synchronized void syncConfig() {
        def otlpEnabled = config.otlpEnabled && (!isLocalDevelopment || otlpEnabledInLocalDev)
        withDebug(['Syncing config', [prometheus: config.prometheusEnabled, otlp: otlpEnabled]]) {

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
            if (otlpEnabled) {
                Map<String, ?> otlpConf = new LinkedHashMap<String, Object>()
                otlpConf.putAll(config.otlpConfig ?: [:])
                otlpConf.resourceAttributes = otelResourceAttributes.collect { k, v -> "${k}=${v}" }.join(',')
                Map<String, String> conf = prefixKeys('otlp', otlpConf)
                _otlpRegistry = new OtlpMeterRegistry({ String k -> conf[k] } as OtlpConfig, Clock.SYSTEM)
                _otlpRegistry.config().meterFilter(publishFilter)
                regs.add(_otlpRegistry)
            }

            // Publish the new list and re-add all publish registries
            _publishRegistries = regs
            _publishRegistries.each { registry.add(it) }
        }
    }

    private static Map<String, String> prefixKeys(String prefix, Map<String, ?> config) {
        (config ?: [:]).collectEntries { k, v -> ["${prefix}.${k}".toString(), v?.toString()] }
    }

    private MetricsConfig getConfig() {
        configService.getObject(MetricsConfig)
    }


    private static List<Tag> toTags(Map<String, String> tags) {
        tags?.collect { k, v -> Tag.of(k, v) } ?: []
    }

    private static String applyOwnerPrefix(String name, BaseService owner, boolean useNamePrefix) {
        def prefix = useNamePrefix ? owner?.telemetryPrefix : null
        prefix ? "${prefix}.${name}" : name
    }

    private static Map<String, String> applyOwnerTag(Map<String, String> tags, BaseService owner) {
        if (!owner) return tags
        def merged = new LinkedHashMap<String, String>()
        merged['xh.owner'] = owner.class.name
        if (tags) merged.putAll(tags)
        merged
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
