/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.trace

import grails.async.Promises
import groovy.transform.CompileStatic
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import io.xh.hoist.BaseService
import jakarta.servlet.http.HttpServletRequest
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase

import static java.util.Collections.emptySet

/**
 * Support service for {@link TraceService}.
 *
 * Supports W3C trace-context propagation plumbing used by framework-level instrumentation
 * (inbound request filter, outbound, HTTP/proxy clients, cluster task hand-off), and propagates
 * OTel context across Grails Promise worker threads.
 */
@CompileStatic
class TraceContextService extends BaseService {

    TraceService traceService

    void init() {
        installPromiseContextPropagation()
    }

    //--------------------------------
    // HTTP
    //---------------------------------
    /**
     * Inject W3C trace context onto an outbound HTTP request.
     * No-op if tracing is disabled or no active span exists.
     */
    void injectContext(HttpUriRequestBase request) {
        def sdk = traceService.otelSdk
        if (!sdk || !Span.current().spanContext.valid) return
        sdk.propagators.textMapPropagator.inject(Context.current(), request, HTTP_SETTER)
    }

    /**
     * Capture the current trace context as a W3C traceparent string.
     * Returns null if tracing is disabled or no active span exists.
     */
    String captureTraceparent() {
        def sdk = traceService.otelSdk
        if (!sdk || !Span.current().spanContext.valid) return null
        Map<String, String> carrier = [:]
        sdk.propagators.textMapPropagator.inject(Context.current(), carrier, MAP_SETTER)
        carrier.traceparent
    }

    /**
     * Restore a previously captured traceparent string as the current context.
     *
     * Returns a {@link Scope} that must be closed when done (typically in a finally block),
     * or null if the traceparent is null/empty or tracing is disabled.
     */
    Scope restoreContextFromTraceparent(String traceparent) {
        def sdk = traceService.otelSdk
        if (!traceparent || !sdk) return null
        def context = sdk.propagators.textMapPropagator.extract(Context.current(), [traceparent: traceparent], MAP_GETTER)
        context.makeCurrent()
    }

    /**
     * Restore a W3C trace parent context from incoming HTTP request headers.
     *
     * Returns a {@link Scope} that must be closed when done (typically in a finally block),
     * or null if the traceparent is null/empty or tracing is disabled.
     */
    Scope restoreContextFromRequest(HttpServletRequest request) {
        def sdk = traceService.otelSdk
        if (!sdk) return null
        def context = sdk.propagators.textMapPropagator.extract(Context.current(), request, HTTP_GETTER)
        context.makeCurrent()
    }

    //------------
    // Promises
    //------------
    /**
     * Install a delegating PromiseFactory that propagates OTel trace context to worker
     * threads spawned by Grails {@code task {}} calls. Installed once at startup.
     */
    private void installPromiseContextPropagation() {
        if (!(Promises.promiseFactory instanceof ContextPropagatingPromiseFactory)) {
            Promises.promiseFactory = new ContextPropagatingPromiseFactory(Promises.promiseFactory)
        }
    }

    //------------
    // Carriers
    //------------
    /** Shared getter for extracting trace context from incoming servlet requests. */
    private static final TextMapGetter<HttpServletRequest> HTTP_GETTER =
        new TextMapGetter<HttpServletRequest>() {
            Iterable<String> keys(HttpServletRequest carrier) {
                Collections.list(carrier.headerNames)
            }

            String get(HttpServletRequest carrier, String key) {
                carrier.getHeader(key)
            }
        }

    /** Shared setter for injecting trace context onto outbound Apache HTTP requests. */
    private static final TextMapSetter<HttpUriRequestBase> HTTP_SETTER =
        new TextMapSetter<HttpUriRequestBase>() {
            void set(HttpUriRequestBase carrier, String key, String value) {
                carrier?.setHeader(key, value)
            }
        }

    /** Setter for injecting trace context into a simple Map carrier. */
    private static final TextMapSetter<Map<String, String>> MAP_SETTER =
        new TextMapSetter<Map<String, String>>() {
            void set(Map<String, String> carrier, String key, String value) {
                carrier?.put(key, value)
            }
        }

    /** Getter for extracting trace context from a simple Map carrier. */
    private static final TextMapGetter<Map<String, String>> MAP_GETTER =
        new TextMapGetter<Map<String, String>>() {
            Iterable<String> keys(Map<String, String> carrier) {
                carrier?.keySet() ?: emptySet() as Iterable<String>
            }

            String get(Map<String, String> carrier, String key) {
                carrier?.get(key)
            }
        }
}
