/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry

import groovy.transform.CompileStatic
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.opentelemetry.api.trace.SpanKind
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.Utils

/**
 * Composable builder for wrapping a closure with tracing, logging, and metrics.
 *
 * Each concern is opt-in via dedicated builder methods, then executed with {@link #run}.
 * The closure is wrapped according to the precedence below, regardless of the order the
 * methods are called in:
 *      span → log → timer → counter → user closure.
 *
 * <pre>
 * observe()
 *     .span(name: 'processOrder', tags: [orderId: id])
 *     .logInfo('Processing order')
 *     .timer(orderTimer)
 *     .run {
 *         // business logic
 *     }
 * </pre>
 *
 * When multiple log levels are configured, the finest enabled level is selected at
 * {@code run()} time. This allows callers to specify e.g. both {@code logInfo} and
 * {@code logDebug} — if the logger has DEBUG enabled, the debug message is used;
 * otherwise it falls back to info.
 *
 * @see io.xh.hoist.BaseService#observe() — convenience factory for service callers
 */
@CompileStatic
class ObservedRun {

    private final Object caller

    // Log support — up to one entry per level
    private Object infoMsgs, debugMsgs, traceMsgs

    // Span support
    private Map spanArgs //map matching TraceService.createSpan args
    private SpanRef activeSpan = SpanRef.NOOP

    // Metrics support
    private Timer timer
    private Counter counter

    private ObservedRun(Object caller) {
        this.caller = caller
    }


    /**
     * Create an ObservedRun with the given caller.
     *
     * @param caller object owning the observed work — typically a service or other {@link LogSupport}
     *     implementor. Used to set the span {@code code.namespace} attribute and as the logging context
     *     for {@link #logInfo}, {@link #logDebug}, and {@link #logTrace}. May be null for anonymous usage
     *     that does not require logging.
     */
    static ObservedRun observe(Object caller = null) {
        new ObservedRun(caller)
    }

    //---------------------------
    // Log configuration
    //---------------------------
    /** Log at INFO level via {@link LogSupport#withInfo}. Requires caller to implement {@link LogSupport}. */
    ObservedRun logInfo(Object msgs) {
        requireLogSupport()
        infoMsgs = msgs
        this
    }

    /** Log at DEBUG level via {@link LogSupport#withDebug}. Requires caller to implement {@link LogSupport}. */
    ObservedRun logDebug(Object msgs) {
        requireLogSupport()
        debugMsgs = msgs
        this
    }

    /** Log at TRACE level via {@link LogSupport#withTrace}. Requires caller to implement {@link LogSupport}. */
    ObservedRun logTrace(Object msgs) {
        requireLogSupport()
        traceMsgs = msgs
        this
    }

    //---------------------------
    // Span configuration
    //---------------------------
    /** Configure a trace span. See {@link TraceService#createSpan} for parameter documentation. */
    @NamedVariant
    ObservedRun span(
        @NamedParam(required = true) String name,
        @NamedParam SpanKind kind = SpanKind.INTERNAL,
        @NamedParam Map<String, ?> tags = [:]
    ) {
        spanArgs = [name: name, kind: kind, tags: tags, caller: caller]
        this
    }

    //---------------------------
    // Metrics configuration
    //---------------------------
    /** Record elapsed time on a pre-registered Micrometer {@link Timer}. */
    ObservedRun timer(Timer timer) {
        this.timer = timer
        this
    }

    /** Record elapsed time, auto-registering a tag-free {@link Timer} with the given metric name. */
    ObservedRun timer(String name) {
        timer = Timer.builder(name).register(metricsService.registry)
        this
    }

    /** Increment a pre-registered Micrometer {@link Counter} (counts attempts, not completions). */
    ObservedRun counter(Counter counter) {
        this.counter = counter
        this
    }

    /** Increment an auto-registered tag-free {@link Counter} with the given metric name. */
    ObservedRun counter(String name) {
        counter = Counter.builder(name).register(metricsService.registry)
        this
    }

    //---------------------------
    // Terminal
    //---------------------------
    /**
     * Execute the closure with all configured observability.
     *
     * Wrapping order (outermost → innermost): span → log → counter → timer -> closure.
     * The closure may optionally accept a {@link SpanRef} parameter.
     */
    <T> T run(Closure<T> c) {
        Closure onion = c.maximumNumberOfParameters > 0 ? { -> c.call(activeSpan) } : c
        onion = wrapWithTimer(onion)
        onion = wrapWithCounter(onion)
        onion = wrapWithLog(onion)
        onion = wrapWithSpan(onion)
        return onion.call() as T
    }


    //------------------------------------------------------
    // Implementation
    //------------------------------------------------------
    private Closure wrapWithSpan(Closure inner) {
        if (!spanArgs) return inner
        return { ->
            traceService.withSpan(spanArgs) { SpanRef span ->
                activeSpan = span
                inner.call()
            }
        }
    }

    private Closure wrapWithCounter(Closure inner) {
        return counter ? { -> counter.increment(); inner.call() } : inner
    }

    private Closure wrapWithTimer(Closure inner) {
        return timer ? { -> timer.recordCallable(inner) } : inner
    }

    private Closure wrapWithLog(Closure inner) {
        if (!traceMsgs && !debugMsgs && !infoMsgs) return inner

        LogSupport ls = (LogSupport) caller

        // Select the finest enabled level when multiple are configured
        if (traceMsgs && ls.instanceLog.isTraceEnabled()) {
            return { -> ls.withTrace(traceMsgs, inner) }
        }
        if (debugMsgs && ls.instanceLog.isDebugEnabled()) {
            return { -> ls.withDebug(debugMsgs, inner) }
        }
        if (infoMsgs) {
            return { -> ls.withInfo(infoMsgs, inner) }
        }

        return inner
    }

    private static TraceService getTraceService() {
        Utils.appContext.getBean(TraceService)
    }

    private static MetricsService getMetricsService() {
        Utils.appContext.getBean(MetricsService)
    }

    private void requireLogSupport() {
        if (!(caller instanceof LogSupport)) {
            throw new RuntimeException('ObservedRun requires a LogSupport caller for log methods')
        }
    }
}
