/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.trace

import groovy.transform.CompileStatic
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.TracerBuilder
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.context.propagation.ContextPropagators

/**
 * An {@link OpenTelemetry} facade for JDBC DataSource instrumentation that resolves its
 * backing SDK on every lookup via a caller-supplied closure.
 *
 * {@link TraceService} tears down and rebuilds its internal SDK when {@code xhTraceConfig}
 * changes. Library-style instrumentation (e.g. {@code JdbcTelemetry}) typically captures a
 * {@link Tracer} reference at wrap time and holds it for the life of the wrapped resource —
 * so a direct reference would become stale (or shut down) after any config change.
 *
 * This class bridges the gap with per-call delegation all the way down to
 * {@link Tracer#spanBuilder}. The resolver closure is invoked on every lookup and may return
 * {@code null} to signal that tracing is currently disabled — in which case the wrapped
 * DataSource emits no-op spans with negligible overhead.
 */
@CompileStatic
class DelegatingOpenTelemetry implements OpenTelemetry {

    private final Closure<OpenTelemetry> _resolver
    private final TracerProvider _tracerProvider

    DelegatingOpenTelemetry(Closure<OpenTelemetry> resolver) {
        _resolver = resolver
        _tracerProvider = new DelegatingTracerProvider(resolver)
    }

    @Override
    TracerProvider getTracerProvider() {
        _tracerProvider
    }

    @Override
    ContextPropagators getPropagators() {
        _resolver.call()?.propagators ?: ContextPropagators.noop()
    }


    @CompileStatic
    private static class DelegatingTracerProvider implements TracerProvider {
        private final Closure<OpenTelemetry> _resolver
        DelegatingTracerProvider(Closure<OpenTelemetry> resolver) { _resolver = resolver }

        @Override
        Tracer get(String name) {
            new DelegatingTracer(_resolver, name, null)
        }

        @Override
        Tracer get(String name, String version) {
            new DelegatingTracer(_resolver, name, version)
        }

        // The default `tracerBuilder()` on TracerProvider returns a no-op TracerBuilder that
        // ignores `this` — so callers like JdbcTelemetry's InstrumenterBuilder, which use
        // `tracerProvider.tracerBuilder(name).build()`, would bypass our delegation entirely.
        // Override to route the resulting Tracer through `get()` like the older API.
        @Override
        TracerBuilder tracerBuilder(String name) {
            new DelegatingTracerBuilder(_resolver, name)
        }
    }

    @CompileStatic
    private static class DelegatingTracerBuilder implements TracerBuilder {
        private final Closure<OpenTelemetry> _resolver
        private final String _name
        private String _version

        DelegatingTracerBuilder(Closure<OpenTelemetry> resolver, String name) {
            _resolver = resolver
            _name = name
        }

        @Override
        TracerBuilder setSchemaUrl(String schemaUrl) { this }

        @Override
        TracerBuilder setInstrumentationVersion(String version) {
            _version = version
            this
        }

        @Override
        Tracer build() {
            new DelegatingTracer(_resolver, _name, _version)
        }
    }

    @CompileStatic
    private static class DelegatingTracer implements Tracer {
        private final Closure<OpenTelemetry> _resolver
        private final String _name
        private final String _version

        DelegatingTracer(Closure<OpenTelemetry> resolver, String name, String version) {
            _resolver = resolver
            _name = name
            _version = version
        }

        @Override
        SpanBuilder spanBuilder(String spanName) {
            def ot = _resolver.call()
            def provider = ot?.tracerProvider ?: TracerProvider.noop()
            def tracer = _version ? provider.get(_name, _version) : provider.get(_name)
            tracer.spanBuilder(spanName)
        }
    }
}
