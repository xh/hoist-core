/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.trace.impl

import groovy.transform.CompileStatic
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.telemetry.trace.ClientSpanData
import io.xh.hoist.telemetry.trace.TraceConfig
import io.xh.hoist.util.Utils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

import static io.xh.hoist.util.DateTimeUtils.SECONDS

/**
 * Singleton service that implements the terminal {@link SpanProcessor} for Hoist tracing.
 *
 * Buffers spans by traceId, makes one keep/drop decision per trace at flush time. Keeps any
 * trace that errored or lacks a completed root; otherwise rolls against the per-trace rate
 * from {@code sampleRules}. Config is read live from {@code xhTraceConfig} — no state is
 * baked in at construction, so config changes can't orphan the in-flight buffer.
 *
 * Server spans arrive via {@link #onEnd}; client spans via {@link #submitClientSpans} as
 * {@link ClientSpanData}. Both buffered as {@link ReadableSpan}.
 *
 * Flushing is driven by {@link #sweep} (timer): root ended → flush; open root or
 * {@code fromHoistClient} with no root yet → wait up to {@code traceTimeoutMs} of silence;
 * otherwise → flush after a short lagging-parent window. On a keep decision, drained spans are
 * handed to {@code TraceService.exportSpans()} for batched export — this service does not own
 * any exporter state.
 */
@CompileStatic
class SpanProcessingService extends BaseService implements SpanProcessor {

    static clearCachesConfigs = ['xhTraceConfig']

    private static final int MAX_SPANS_PER_TRACE = 500
    private static final long LAGGING_PARENT_MS = 10 * SECONDS

    ConfigService configService

    private final ConcurrentHashMap<String, TraceBuffer> _buffers = new ConcurrentHashMap<>()
    private volatile TraceConfig _config

    void init() {
        createTimer(name: 'sweep', runFn: this.&sweep, interval: 5 * SECONDS)
    }

    //-----------------------------------
    // Public hooks
    //-----------------------------------
    /**
     * Accept client-submitted spans: wrap as {@link ClientSpanData} (with server-stamped
     * common attrs) and merge into the same per-traceId buffer as server spans. When tail
     * sampling is disabled, hand spans straight to the export pipeline instead.
     */
    void submitClientSpans(List<Map> spans, Resource resource) {
        def extras = commonAttrs()
        def wrapped = spans.collect { Map raw -> new ClientSpanData(raw, resource, extras) }
        if (!config.tailSamplingEnabled) {
            Utils.traceService.exportSpans(wrapped as List<ReadableSpan>)
            return
        }
        wrapped.each { span ->
            def buffer = getOrCreateBuffer(span.spanContext.traceId)
            buffer?.addSpan(span)
        }
    }

    /** Server-authoritative attributes stamped on every span (server and client). */
    Map<String, Object> commonAttrs() {
        def attrs = [:] as Map<String, Object>
        def identityService = Utils.identityService,
            authUsername = identityService?.authUsername,
            username = identityService?.username
        if (authUsername) {
            attrs['user.name'] = authUsername
            if (authUsername != username) attrs['xh.impersonating'] = username
        }
        attrs['xh.isPrimary'] = Utils.clusterService.isPrimary
        return attrs
    }

    //-----------------------------------
    // SpanProcessor contract
    //-----------------------------------
    boolean isStartRequired() { true }
    boolean isEndRequired() { true }

    void onStart(Context ctx, ReadWriteSpan span) {
        commonAttrs().each { k, v -> setAttr(span, k, v) }
        if (!config.tailSamplingEnabled) return
        def buffer = getOrCreateBuffer(span.spanContext.traceId)
        if (buffer) {
            // Record the root early so the sweeper can tell "open root" from "no root arrived."
            if (!span.parentSpanContext.isValid()) buffer.root = span
            buffer.noteActivity()
        }
    }

    void onEnd(ReadableSpan span) {
        if (!config.tailSamplingEnabled) {
            Utils.traceService.exportSpans([span])
            return
        }
        def buffer = _buffers.get(span.spanContext.traceId)
        buffer?.addSpan(span)
    }

    /**
     * No-op: called by SDK on TracerProvider rebuild. We intentionally preserve buffers across
     * config/exporter changes. App shutdown flushing is handled via BaseService.destroy() if
     * needed — for now we accept dropping anything in flight at JVM stop.
     */
    CompletableResultCode shutdown() { CompletableResultCode.ofSuccess() }

    CompletableResultCode forceFlush() { CompletableResultCode.ofSuccess() }


    //-----------------------------------
    // Implementation
    //-----------------------------------
    void sweep() {
        def cfg = config
        // Drain any leftover buffers immediately if tail sampling has been turned off.
        // Pass-through mode means "export everything," so skip the keep/drop roll.
        if (!cfg.tailSamplingEnabled) {
            _buffers.values().each { buffer ->
                if (_buffers.remove(buffer.traceId, buffer)) {
                    Utils.traceService.exportSpans(buffer.drainSpans())
                }
            }
            return
        }
        def timeoutCutoff = System.currentTimeMillis() - cfg.traceTimeoutMs,
            laggingParentCutoff = System.currentTimeMillis() - LAGGING_PARENT_MS
        _buffers.values().each { TraceBuffer buffer ->
            def root = buffer.root,
                lastActivityMs = buffer.lastActivityMs,
                fromHoistClient = buffer.fromHoistClient

            // 1) Happy path — root completed, trace is done.
            if (root?.hasEnded()) {
                flushAndRemove(buffer, cfg)
                return
            }

            // 2) Timeout paths.
            if (root != null || fromHoistClient) {
                // Running root, or no root yet but it's in a Hoist client still working on it.
                if (lastActivityMs < timeoutCutoff) {
                    logWarn("Trace timed out after ${cfg.traceTimeoutMs}ms of inactivity", [traceId: buffer.traceId])
                    flushAndRemove(buffer, cfg)
                }
            } else {
                // No parent arrived — external parent we'll never see, or a missing local parent.
                // Short wait to avoid flushing mid-stream, then drop-or-export.
                if (lastActivityMs < laggingParentCutoff) flushAndRemove(buffer, cfg)
            }
        }
    }

    private TraceBuffer getOrCreateBuffer(String traceId) {
        def existing = _buffers.get(traceId)
        if (existing) return existing

        def cfg = config
        def size = _buffers.size()
        if (size >= cfg.maxBufferedTraces) return null
        if (size == cfg.maxBufferedTraces - 1) {
            logWarn('Span buffer reached max. Next trace may be skipped.', [limit: cfg.maxBufferedTraces])
        }
        def created = new TraceBuffer(traceId)
        return _buffers.putIfAbsent(traceId, created) ?: created
    }

    private void flushAndRemove(TraceBuffer buffer, TraceConfig cfg) {
        if (!_buffers.remove(buffer.traceId, buffer)) return
        if (!shouldKeep(buffer, cfg)) return
        Utils.traceService.exportSpans(buffer.drainSpans())
    }

    private boolean shouldKeep(TraceBuffer buffer, TraceConfig cfg) {
        // Keep on error or when we lack context to decide (no root, or root never ended).
        return buffer.hasError ||
            !buffer.root?.hasEnded() ||
            ThreadLocalRandom.current().nextDouble() < getSampleRate(buffer, cfg)
    }

    private double getSampleRate(TraceBuffer buffer, TraceConfig cfg) {
        def rules = cfg.sampleRules
        if (!rules) return cfg.sampleRate
        try {
            def rootName = buffer.rootName
            def rootTags = buffer.rootTags
            for (Map rule in rules) {
                Map match = rule.match as Map
                if (match?.every { k, v ->
                        matchesValue(k == 'name' ? rootName : rootTags?.get(k), v)
                    } && rule.sampleRate instanceof Number) {
                    return ((Number) rule.sampleRate).doubleValue()
                }
            }
            return cfg.sampleRate
        } catch (Exception e) {
            logError('Failed to compute sample rate for trace', e)
            return 0d
        }
    }

    private static boolean matchesValue(Object actual, Object pattern) {
        if (!(actual instanceof String) || !(pattern instanceof String)) return actual == pattern
        def patternStr = pattern as String,
            actualStr = actual as String
        if (patternStr == '*') return true
        def startsWithWild = patternStr.startsWith('*'),
            endsWithWild = patternStr.endsWith('*'),
            core = patternStr.replaceAll('^\\*|\\*$', '')
        if (startsWithWild && endsWithWild) return actualStr.contains(core)
        if (startsWithWild) return actualStr.endsWith(core)
        if (endsWithWild) return actualStr.startsWith(core)
        return actual == pattern
    }

    private static void setAttr(ReadWriteSpan span, String k, Object v) {
        if (v instanceof Boolean) span.setAttribute(k, v)
        else if (v instanceof Number) span.setAttribute(k, v.longValue())
        else span.setAttribute(k, v.toString())
    }

    private TraceConfig getConfig() {
        _config ?= new TraceConfig(configService.getMap('xhTraceConfig'))
    }


    void clearCaches() {
        super.clearCaches()
        _config = null
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhTraceConfig'),
        bufferedTraces: _buffers.size()
    ]}

    //------------------------------------------
    // TraceBuffer — per-trace span accumulator
    //------------------------------------------
    @CompileStatic
    private static class TraceBuffer {
        private static final Logger log = LoggerFactory.getLogger(SpanProcessingService)
        private static final AttributeKey<Boolean> FROM_HOIST_CLIENT_KEY = AttributeKey.booleanKey('xh.fromHoistClient')

        final String traceId

        private List<ReadableSpan> _completedSpans = []
        private volatile long _lastActivityMs = System.currentTimeMillis()
        volatile boolean hasError
        volatile boolean overflowed
        volatile boolean fromHoistClient
        volatile ReadableSpan root

        TraceBuffer(String traceId) {
            this.traceId = traceId
        }

        synchronized void addSpan(ReadableSpan span) {
            if (_completedSpans.size() >= MAX_SPANS_PER_TRACE) {
                if (!overflowed) {
                    overflowed = true
                    log.warn("Trace truncated — span cap reached [traceId={}, limit={}]", traceId, MAX_SPANS_PER_TRACE)
                }
                return
            }
            _completedSpans << span
            def data = span.toSpanData()
            if (data.status.statusCode == StatusCode.ERROR) hasError = true
            if (!span.parentSpanContext.isValid()) root = span
            if (data.attributes.get(FROM_HOIST_CLIENT_KEY)) fromHoistClient = true
            noteActivity()
        }

        synchronized List<ReadableSpan> drainSpans() {
            def out = _completedSpans
            _completedSpans = []
            return out
        }

        synchronized int size() { _completedSpans.size() }

        long getLastActivityMs() { _lastActivityMs }

        void noteActivity() { _lastActivityMs = System.currentTimeMillis() }

        String getRootName() { root?.name }

        Map<String, Object> getRootTags() {
            def attrs = root?.toSpanData()?.attributes
            if (!attrs) return null
            def result = [:] as Map<String, Object>
            attrs.forEach { AttributeKey k, Object v -> result[k.key] = v }
            return result
        }
    }
}
