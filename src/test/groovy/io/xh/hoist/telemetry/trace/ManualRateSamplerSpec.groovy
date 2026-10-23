/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry.trace

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.samplers.SamplingDecision
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.CompletableFuture

class ManualRateSamplerSpec extends Specification {

    static final String TRACE_ID = '0af7651916cd43dd8448eb211c80319c'
    static final String SPAN_ID = 'b7ad6b7169203331'

    @Subject
    ManualRateSampler sampler = new ManualRateSampler()

    def cleanup() {
        sampler.clearSampleRate()
    }

    def 'root span is sampled when rate is 1'() {
        given:
        sampler.setSampleRate(1d)

        expect:
        decide(Context.root()) == SamplingDecision.RECORD_AND_SAMPLE
    }

    def 'root span is dropped when rate is 0'() {
        given:
        sampler.setSampleRate(0d)

        expect:
        decide(Context.root()) == SamplingDecision.DROP
    }

    def 'root span is dropped when no rate was ever set'() {
        expect:
        decide(Context.root()) == SamplingDecision.DROP
    }

    def 'clearSampleRate resets to dropping'() {
        given:
        sampler.setSampleRate(1d)
        sampler.clearSampleRate()

        expect:
        decide(Context.root()) == SamplingDecision.DROP
    }

    def 'parent sampled=#parentSampled with rate #rate yields #expected'() {
        given:
        sampler.setSampleRate(rate)
        def flags = parentSampled ? TraceFlags.getSampled() : TraceFlags.getDefault()
        def parent = Span.wrap(SpanContext.create(TRACE_ID, SPAN_ID, flags, TraceState.getDefault()))
        def ctx = Context.root().with(parent)

        expect: 'a valid parent decision always wins over the rate'
        decide(ctx) == expected

        where:
        parentSampled | rate | expected
        true          | 0d   | SamplingDecision.RECORD_AND_SAMPLE
        false         | 1d   | SamplingDecision.DROP
    }

    def 'invalid parent span falls through to the rate'() {
        given:
        sampler.setSampleRate(1d)
        def ctx = Context.root().with(Span.getInvalid())

        expect:
        decide(ctx) == SamplingDecision.RECORD_AND_SAMPLE
    }

    def 'sample rate is thread-local'() {
        given:
        sampler.setSampleRate(1d)

        when:
        def other = CompletableFuture.supplyAsync { decide(Context.root()) }.get()

        then:
        other == SamplingDecision.DROP
        decide(Context.root()) == SamplingDecision.RECORD_AND_SAMPLE
    }

    def 'a fractional rate samples roughly that proportion'() {
        given:
        sampler.setSampleRate(0.5d)

        when:
        int sampled = (1..10_000).count { decide(Context.root()) == SamplingDecision.RECORD_AND_SAMPLE }

        then:
        sampled > 4_000
        sampled < 6_000
    }

    def 'describes itself'() {
        expect:
        sampler.description == 'ManualRateSampler'
    }

    private SamplingDecision decide(Context ctx) {
        sampler.shouldSample(ctx, TRACE_ID, 'span', SpanKind.INTERNAL, Attributes.empty(), []).decision
    }
}
