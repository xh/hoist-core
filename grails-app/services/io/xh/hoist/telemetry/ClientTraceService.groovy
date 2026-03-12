/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry

import groovy.transform.CompileStatic
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import io.xh.hoist.BaseService

import static java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Receives client-side spans from the browser and relays them through the server's
 * OTel export pipeline.
 *
 * @internal - not intended for direct use by applications.
 */
@CompileStatic
class ClientTraceService extends BaseService {

    TraceService traceService

    /**
     * Submit client-side spans received from the browser's TracingService.
     *
     * Converts the client span JSON into OTel {@link SpanData} objects and exports them
     * through the configured export pipeline, preserving the original trace/span IDs so
     * that client and server spans form a coherent distributed trace.
     *
     * Spans are filtered using the same deterministic trace-ID-based sampling as
     * server-originated spans, ensuring consistent sampling across both paths.
     *
     * @param spans - list of span maps as serialized by the client {@code Span.toJSON()}
     */
    void submitClientSpans(List<Map> spans) {
        def exporters = traceService.exporters,
            resource = traceService.tracingResource
        if (!traceService.enabled || !spans || !exporters) return

        def data = spans
            .findAll { traceService.shouldSample(it.traceId as String) }
            .collect { new ClientSpanData(it, resource) } as List<SpanData>

        if (data) {
            exporters.each { it.export(data) }
        }
    }

    /**
     * {@link SpanData} implementation for client-submitted spans relayed through the
     * server's OTel export pipeline.
     *
     * Preserves the original trace/span IDs from the client so that client and server
     * spans form a coherent distributed trace in the collector.
     */
    private static class ClientSpanData implements SpanData {

        // LIBRARY for backward compatibility
        private static final InstrumentationScopeInfo SCOPE = InstrumentationScopeInfo.create('io.xh.hoist.client')
        private static final InstrumentationLibraryInfo LIBRARY = InstrumentationLibraryInfo.create('io.xh.hoist.client', null)

        private final SpanContext _spanContext
        private final SpanContext _parentSpanContext
        private final Resource _resource
        private final String _name
        private final SpanKind _kind
        private final long _startEpochNanos
        private final long _endEpochNanos
        private final Attributes _attributes
        private final List<EventData> _events
        private final StatusData _status

        ClientSpanData(Map span, Resource resource) {
            _resource = resource
            _name = span.name as String

            _spanContext = SpanContext.create(
                span.traceId as String,
                span.spanId as String,
                TraceFlags.sampled,
                TraceState.default
            )

            def parentId = span.parentSpanId as String
            _parentSpanContext = parentId
                ? SpanContext.create(span.traceId as String, parentId, TraceFlags.sampled, TraceState.default)
                : SpanContext.invalid

            _startEpochNanos = MILLISECONDS.toNanos(span.startTime as long)
            _endEpochNanos = MILLISECONDS.toNanos(span.endTime as long)

            def tags = span.tags as Map ?: [:]
            _kind = parseSpanKind(span.kind as String)

            // Build attributes from client tags
            def ab = Attributes.builder()
            tags.each { k, v -> ab.put(AttributeKey.stringKey(k as String), v as String) }
            _attributes = ab.build()

            // Events (e.g. exception recordings)
            _events = ((span.events as List<Map>) ?: []).collect { Map evt ->
                def evtAttrs = Attributes.builder()
                (evt.attributes as Map)?.each { k, v ->
                    evtAttrs.put(AttributeKey.stringKey(k as String), v as String)
                }
                def attrs = evtAttrs.build()
                EventData.create(
                    MILLISECONDS.toNanos(evt.timestamp as long),
                    evt.name as String,
                    attrs,
                    attrs.size()
                )
            }

            // Status
            switch (span.status as String) {
                case 'ok':    _status = StatusData.ok(); break
                case 'error': _status = StatusData.create(StatusCode.ERROR, ''); break
                default:      _status = StatusData.unset()
            }
        }

        SpanContext getSpanContext() { _spanContext }
        SpanContext getParentSpanContext() { _parentSpanContext }
        Resource getResource() { _resource }
        InstrumentationScopeInfo getInstrumentationScopeInfo() { SCOPE }
        InstrumentationLibraryInfo getInstrumentationLibraryInfo() {LIBRARY}
        String getName() { _name }
        SpanKind getKind() { _kind }
        long getStartEpochNanos() { _startEpochNanos }
        long getEndEpochNanos() { _endEpochNanos }
        Attributes getAttributes() { _attributes }
        List<EventData> getEvents() { _events }
        List<LinkData> getLinks() { [] }
        StatusData getStatus() { _status }
        boolean hasEnded() { true }
        int getTotalRecordedEvents() { _events.size() }
        int getTotalRecordedLinks() { 0 }
        int getTotalAttributeCount() { _attributes.size() }


        private static SpanKind parseSpanKind(String kind) {
            switch (kind) {
                case 'client':   return SpanKind.CLIENT
                case 'server':   return SpanKind.SERVER
                case 'producer': return SpanKind.PRODUCER
                case 'consumer': return SpanKind.CONSUMER
                default:         return SpanKind.INTERNAL
            }
        }
    }
}
