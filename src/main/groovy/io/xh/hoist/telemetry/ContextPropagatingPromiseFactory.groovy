/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry

import grails.async.Promise
import grails.async.PromiseFactory
import io.opentelemetry.context.Context

/**
 * Delegating {@link PromiseFactory} that automatically propagates the current OTel trace
 * context to worker threads spawned by Grails {@code task {}} calls.
 *
 * Installed once at startup by {@link TraceService}. When tracing is disabled or no span
 * is active, the context capture/restore is a no-op with negligible overhead.
 *
 * This is the Grails equivalent of Spring Boot's auto-configured context-propagating
 * {@code TaskExecutor} — necessary because Grails' {@code Promises.task} uses its own
 * internal executor that Spring Boot's auto-configuration does not wrap.
 */
class ContextPropagatingPromiseFactory implements PromiseFactory {

    @Delegate PromiseFactory delegate

    ContextPropagatingPromiseFactory(PromiseFactory delegate) {
        this.delegate = delegate
    }

    @Override
    <T> Promise<T> createPromise(Closure<T>... closures) {
        def ctx = Context.current()
        def wrapped = closures.collect { Closure<T> c ->
            { -> def scope = ctx.makeCurrent()
                try { return c.call() } finally { scope.close() }
            } as Closure<T>
        } as Closure<T>[]
        delegate.createPromise(wrapped)
    }
}
