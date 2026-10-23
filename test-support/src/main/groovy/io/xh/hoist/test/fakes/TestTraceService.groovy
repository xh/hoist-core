/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test.fakes

import groovy.transform.CompileStatic
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.xh.hoist.telemetry.trace.TraceService

/**
 * {@link TraceService} for tests. Tracing is never enabled, so `withSpan()` - including
 * `BaseService.span()` and `observe().span()` - runs its closure with the shared no-op `SpanRef`
 * and nothing is exported. `xhTraceConfig` is never read. Registered by
 * {@link io.xh.hoist.test.HoistUnitTest} as the application's `traceService` bean.
 */
@CompileStatic
class TestTraceService extends TraceService {

    // The overrides below skip TraceService's config sync, which would read xhTraceConfig.
    // Note clearCaches() cannot call super - the parent's implementation runs that same sync.
    @Override
    void init() {}

    @Override
    void clearCaches() {}

    @Override
    void addExporter(SpanExporter exporter) {}

    @Override
    void removeExporter(SpanExporter exporter) {}
}
