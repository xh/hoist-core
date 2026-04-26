/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.trace

import groovy.transform.CompileStatic
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.util.DateTimeUtils
import io.xh.hoist.util.Utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static java.lang.System.currentTimeMillis

/**
 * Singleton service that implements an optional buffering {@link SpanProcessor} for tracing.
 *
 * Buffers spans by traceId, makes one keep/drop decision per trace at flush time. Keeps any
 * trace that errored or lacks a completed root; otherwise rolls against the per-trace rate
 * from {@code sampleRules}. Config is read live from {@code xhTraceConfig} — no state is
 * baked in at construction, so config changes can't orphan the in-flight buffer.
 *
 * Server spans arrive via {@link #onEnd}; client spans via {@link #submitSpan}.
 * Both buffered as {@link ReadableSpan}.
 *
 * Flushing is driven by {@link #processTraces} (timer): root ended → flush; open root or
 * {@code rootAtHoistClient} with no root yet → wait up to {@code traceTimeoutMs} of silence;
 * otherwise → flush after a short lagging-parent window. On a keep decision, drained spans are
 * handed to {@code TraceService.exportProcessor} — this service does not own any exporter state.
 */
@CompileStatic
class TailSamplingService extends BaseService implements SpanProcessor {

    static clearCachesConfigs = ['xhTraceConfig']

    ConfigService configService

    private static final int MAX_SPANS_PER_TRACE = 500
    private static final long LAGGING_PARENT_MS = 10 * SECONDS
    private final ConcurrentHashMap<String, TraceBuffer> _buffers = new ConcurrentHashMap<>()
    private volatile TraceConfig _config

    void init() {
        loadConfig()
        createTimer(name: 'processTraces', runFn: this.&processTraces, interval: 5 * SECONDS)
    }

    //-----------------------------------
    // Public hooks
    //-----------------------------------
    /**
     * Accept an external span that won't arrive via SpanProcessor API (e.g. client spans)
     */
    void submitSpan(ReadableSpan span) {
        def buffer = getOrCreateBuffer(span.spanContext.traceId)
        if (buffer) {
            buffer.noteSpanStarted(span)
            buffer.noteSpanComplete(span)
        }
    }

    //-----------------------------------
    // SpanProcessor contract
    //-----------------------------------
    boolean isStartRequired() { true }
    boolean isEndRequired() { true }

    void onStart(Context ctx, ReadWriteSpan span) {
        getOrCreateBuffer(span.spanContext.traceId)?.noteSpanStarted(span)
    }

    void onEnd(ReadableSpan span) {
        _buffers.get(span.spanContext.traceId)?.noteSpanComplete(span)
    }

    CompletableResultCode shutdown() { CompletableResultCode.ofSuccess() }
    CompletableResultCode forceFlush() { CompletableResultCode.ofSuccess() }


    //-----------------------------------
    // Implementation
    //-----------------------------------
    private void processTraces() {
        // Sampling disabled — drain anything still buffered (e.g. from before a runtime flip)
        if (!_config.sampleEnabled) {
            _buffers.values().each { flushAndRemove(it) }
            return
        }

        def timeoutCutoff = currentTimeMillis() - _config.traceTimeoutMs,
            laggingParentCutoff = currentTimeMillis() - LAGGING_PARENT_MS
        _buffers.values().each { TraceBuffer buffer ->
            def root = buffer.root,
                lastActivityMs = buffer.lastActivityMs,
                rootAtHoistClient = buffer.rootAtHoistClient

            // 1) Happy path — root completed, trace is done.
            if (root?.hasEnded()) {
                flushAndRemove(buffer)
                return
            }

            // 2) Timeout paths.I
            if (root != null || rootAtHoistClient) {
                // Running root, or no root yet but it's in a Hoist client still working on it.
                if (lastActivityMs < timeoutCutoff) {
                    logWarn("Trace timed out after ${_config.traceTimeoutMs}ms of inactivity", [traceId: buffer.traceId])
                    flushAndRemove(buffer)
                }
            } else {
                // No parent arrived — external parent we'll never see, or a missing local parent.
                // Short wait to avoid flushing mid-stream, then drop-or-export.
                if (lastActivityMs < laggingParentCutoff) flushAndRemove(buffer)
            }
        }
    }

    private TraceBuffer getOrCreateBuffer(String traceId) {
        def existing = _buffers.get(traceId)
        if (existing) return existing

        def cfg = _config,
            size = _buffers.size()
        if (size >= cfg.maxBufferedTraces) return null
        if (size == cfg.maxBufferedTraces - 1) {
            logWarn('Span buffer reached max. Next trace may be skipped.', [limit: cfg.maxBufferedTraces])
        }
        def created = new TraceBuffer(traceId)
        return _buffers.putIfAbsent(traceId, created) ?: created
    }

    private void flushAndRemove(TraceBuffer buffer) {
        if (!_buffers.remove(buffer.traceId, buffer)) return
        if (shouldKeep(buffer)) {
            buffer.drainSpans().each { traceService.exportProcessor.submitSpan(it) }
        }
    }

    private boolean shouldKeep(TraceBuffer buffer) {
        // Keep on error or when we lack context to decide (no root, or root never ended).
        return buffer.hasError ||
            !buffer.root?.hasEnded() ||
            ThreadLocalRandom.current().nextDouble() < getSampleRate(buffer)
    }

    private double getSampleRate(TraceBuffer buffer) {
        def enabled = _config.sampleEnabled,
            rules = _config.sampleRules,
            rate = _config.sampleRate
        if (!enabled) return 1d
        if (!rules) return rate
        try {
            def rootName = buffer.root?.name,
                rootTags = buffer.rootTags
            for (Map rule in rules) {
                Map match = rule.match as Map
                if (match?.every { k, v ->
                        matchesValue(k == 'name' ? rootName : rootTags?.get(k), v)
                    } && rule.sampleRate instanceof Number) {
                    return ((Number) rule.sampleRate).doubleValue()
                }
            }
            return rate
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

    private void loadConfig() {
        _config = new TraceConfig(configService.getMap('xhTraceConfig'))
    }

    void clearCaches() {
        super.clearCaches()
        loadConfig()
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhTraceConfig'),
        bufferedTraces: _buffers.size()
    ]}

    //------------------------------------------
    // TraceBuffer — per-trace span accumulator
    //------------------------------------------
    @CompileStatic
    private class TraceBuffer {
        final String traceId

        private List<ReadableSpan> _completedSpans = []
        private volatile long _lastActivityMs = currentTimeMillis()
        volatile boolean hasError
        volatile boolean overflowed
        volatile boolean rootAtHoistClient
        volatile ReadableSpan root

        TraceBuffer(String traceId) {
            this.traceId = traceId
        }

        synchronized List<ReadableSpan> drainSpans() {
            def out = _completedSpans
            _completedSpans = []
            return out
        }

        long getLastActivityMs() { _lastActivityMs }

        synchronized void noteSpanStarted(ReadableSpan span) {
            def parent = span.parentSpanContext
            if (!parent.isValid()) {
                root = span
            } else if (parent.isRemote() && Utils.currentRequest?.getHeader('X-Hoist-Client') == '1') {
                rootAtHoistClient = true
            }
            _lastActivityMs = currentTimeMillis()
        }

        synchronized void noteSpanComplete(ReadableSpan span) {
            if (_completedSpans.size() >= MAX_SPANS_PER_TRACE) {
                if (!overflowed) {
                    overflowed = true
                    logWarn('Trace truncated — span cap reached', [traceId: traceId, limit: MAX_SPANS_PER_TRACE])
                }
                return
            }
            _completedSpans << span
            if (span.toSpanData().status.statusCode == StatusCode.ERROR) hasError = true
            _lastActivityMs = currentTimeMillis()
        }

        Map<String, Object> getRootTags() {
            def attrs = root?.toSpanData()?.attributes
            if (!attrs) return null
            def result = [:] as Map<String, Object>
            attrs.forEach { AttributeKey k, Object v -> result[k.key] = v }
            return result
        }
    }
}
