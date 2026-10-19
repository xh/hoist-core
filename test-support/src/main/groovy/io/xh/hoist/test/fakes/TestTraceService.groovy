/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test.fakes

import groovy.transform.CompileStatic
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.xh.hoist.telemetry.trace.TraceService

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Recording {@link TraceService} for tests. Tracing is never enabled, so each span is the shared
 * no-op `SpanRef` and nothing is exported. Spans opened via `withSpan()` - including those from
 * `BaseService.span()` and `observe().span()` - are collected in {@link #getSpans}, so a spec can
 * assert that code under test traced the work it should have:
 *
 * <pre>
 * service.refreshPrices()
 *
 * expect:
 * testTraceService.lastSpan.name == 'refreshPrices'
 * testTraceService.lastSpan.tags.source == 'vendor'
 * </pre>
 *
 * Each recorded span is a Map of its `name`, `kind` and `tags` as passed when the span was opened.
 * Span names include any service `telemetryPrefix`, as they would in a running app. Tags set later
 * via `SpanRef.setTag()`, the default tags added by `TraceService` and any error status are not
 * recorded. `xhTraceConfig` is never read, and `addExporter()` / `removeExporter()` do nothing.
 * Registered by {@link io.xh.hoist.test.HoistUnitTest} as the application's `traceService` bean
 * and cleared before each feature.
 */
@CompileStatic
class TestTraceService extends TraceService {

    private final List<Map> entries = new CopyOnWriteArrayList<Map>()

    /** All spans opened so far, in the order they were started. */
    List<Map> getSpans() {
        entries.asImmutable()
    }

    /** The most recently started span, or null if none. */
    Map getLastSpan() {
        entries ? entries.last() : null
    }

    /** Spans opened with the given name. */
    List<Map> spans(String name) {
        entries.findAll { it.name == name }
    }

    /** Forget all recorded spans. */
    void clear() {
        entries.clear()
    }

    @Override
    <T> T withSpan(Map args, Closure<T> c) {
        entries << [name: args.name, kind: args.kind, tags: new LinkedHashMap(args.tags as Map ?: [:])]
            .findAll { k, v -> v != null }
        super.withSpan(args, c)
    }

    /** Tracing is always off, regardless of `xhTraceConfig`. */
    @Override
    boolean isEnabled() {
        false
    }

    @Override
    OpenTelemetrySdk getOtelSdk() {
        null
    }

    // The overrides below skip TraceService's config sync, which would read xhTraceConfig.
    @Override
    void init() {}

    @Override
    void clearCaches() {}

    @Override
    void addExporter(SpanExporter exporter) {}

    @Override
    void removeExporter(SpanExporter exporter) {}
}
