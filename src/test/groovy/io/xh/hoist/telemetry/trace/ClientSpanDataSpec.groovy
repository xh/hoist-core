/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry.trace

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.resources.Resource
import spock.lang.Specification

class ClientSpanDataSpec extends Specification {

    static final String TRACE_ID = '0af7651916cd43dd8448eb211c80319c'
    static final String SPAN_ID = 'b7ad6b7169203331'
    static final String PARENT_ID = '00f067aa0ba902b7'

    def 'preserves client trace and span ids and is sampled'() {
        when:
        def data = build()

        then:
        data.spanContext.traceId == TRACE_ID
        data.spanContext.spanId == SPAN_ID
        data.spanContext.isSampled()
        data.name == 'my-span'
    }

    def 'parentSpanId [#parentId] gives no parent'() {
        expect:
        build(parentSpanId: parentId).parentSpanContext == SpanContext.getInvalid()

        where:
        parentId << [null, '']
    }

    def 'parentSpanId yields a parent context in the same trace'() {
        when:
        def parent = build(parentSpanId: PARENT_ID).parentSpanContext

        then:
        parent.traceId == TRACE_ID
        parent.spanId == PARENT_ID
    }

    def 'an invalid traceId yields an invalid span context'() {
        expect:
        !build(traceId: 'xyz').spanContext.isValid()
    }

    def 'converts millisecond times to nanos'() {
        when:
        def data = build(startTime: 1_000L, endTime: 1_250L)

        then:
        data.startEpochNanos == 1_000_000_000L
        data.endEpochNanos == 1_250_000_000L
        data.latencyNanos == 250_000_000L
        data.hasEnded()
    }

    def 'a missing startTime fails construction'() {
        when:
        build(startTime: null)

        then:
        thrown(Exception)
    }

    def 'kind #kind maps to #expected'() {
        expect:
        build(kind: kind).kind == expected

        where:
        kind       | expected
        'client'   | SpanKind.CLIENT
        'server'   | SpanKind.SERVER
        'producer' | SpanKind.PRODUCER
        'consumer' | SpanKind.CONSUMER
        null       | SpanKind.INTERNAL
        'CLIENT'   | SpanKind.INTERNAL
        'bogus'    | SpanKind.INTERNAL
    }

    def 'status #status with description #desc maps to #code / [#expectedDesc]'() {
        when:
        def result = build(status: status, statusDescription: desc).status

        then:
        result.statusCode == code
        result.description == expectedDesc

        where:
        status  | desc   | code             | expectedDesc
        'ok'    | null   | StatusCode.OK    | ''
        'ok'    | 'note' | StatusCode.OK    | ''
        'error' | 'boom' | StatusCode.ERROR | 'boom'
        'error' | null   | StatusCode.ERROR | ''
        'unset' | null   | StatusCode.UNSET | ''
        null    | null   | StatusCode.UNSET | ''
        'weird' | 'x'    | StatusCode.UNSET | ''
    }

    def 'tag #value is stored as #key'() {
        when:
        def attrs = build(tags: [k: value]).attributes

        then:
        attrs.get(key) == expected

        where:
        value                 | key                          | expected
        5L                    | AttributeKey.longKey('k')    | 5L
        5                     | AttributeKey.longKey('k')    | 5L
        true                  | AttributeKey.booleanKey('k') | true
        1.5d                  | AttributeKey.doubleKey('k')  | 1.5d
        'x'                   | AttributeKey.stringKey('k')  | 'x'
        1.5f                  | AttributeKey.stringKey('k')  | '1.5'
        new BigDecimal('2.5') | AttributeKey.stringKey('k')  | '2.5'
    }

    def 'null tag values are dropped'() {
        expect:
        build(tags: [k: null]).attributes.isEmpty()
    }

    def 'missing tags yield no attributes'() {
        when:
        def data = build()

        then:
        data.attributes.isEmpty()
        data.totalAttributeCount == 0
    }

    def 'extra tags are merged and override client tags'() {
        when:
        def data = build([tags: [a: 'client', b: 'client']], [a: 'server', c: 'server'])

        then:
        data.getAttribute(AttributeKey.stringKey('a')) == 'server'
        data.getAttribute(AttributeKey.stringKey('b')) == 'client'
        data.getAttribute(AttributeKey.stringKey('c')) == 'server'
    }

    def 'null extra tags do not clobber client tags'() {
        when:
        def data = build([tags: [a: 'client']], [a: null])

        then:
        data.getAttribute(AttributeKey.stringKey('a')) == 'client'
    }

    def 'null extraTags map is tolerated'() {
        expect:
        new ClientSpanData(base(tags: [a: 'x']), Resource.empty(), null).totalAttributeCount == 1
    }

    def 'events are converted with typed attributes'() {
        when:
        def data = build(events: [
            [name: 'exception', timestamp: 2_000L, attributes: [count: 3, msg: 'bad']],
            [name: 'bare', timestamp: 3_000L]
        ])

        then:
        data.totalRecordedEvents == 2
        data.events[0].name == 'exception'
        data.events[0].epochNanos == 2_000_000_000L
        data.events[0].attributes.get(AttributeKey.longKey('count')) == 3L
        data.events[0].attributes.get(AttributeKey.stringKey('msg')) == 'bad'
        data.events[1].attributes.isEmpty()
    }

    def 'null events yield an empty list'() {
        expect:
        build().events == []
    }

    def 'satisfies the ReadableSpan and SpanData contract'() {
        when:
        def data = build(tags: [k: 'v'])

        then:
        data.toSpanData().is(data)
        data.getAttribute(AttributeKey.stringKey('k')) == 'v'
        data.links == []
        data.totalRecordedLinks == 0
        data.instrumentationScopeInfo.name == 'io.xh.hoist.client'
        data.resource == Resource.empty()
    }

    private ClientSpanData build(Map overrides = [:], Map extraTags = [:]) {
        new ClientSpanData(base(overrides), Resource.empty(), extraTags)
    }

    private Map base(Map overrides = [:]) {
        [
            name     : 'my-span',
            traceId  : TRACE_ID,
            spanId   : SPAN_ID,
            startTime: 1_000L,
            endTime  : 2_000L
        ] + overrides
    }
}
