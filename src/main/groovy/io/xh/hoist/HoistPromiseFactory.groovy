/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist

import grails.async.Promise
import grails.async.PromiseFactory
import groovy.transform.CompileStatic
import io.opentelemetry.context.Context
import io.xh.hoist.user.HoistIdentity

import static io.xh.hoist.util.Utils.identityService

/**
 * Delegating {@link PromiseFactory} that propagates framework-level thread context — Hoist
 * {@link HoistIdentity} and OTel trace context — to worker threads spawned by Grails
 * {@code task {}} calls.
 *
 * Installed once at startup by {@link HoistCoreGrailsPlugin}. Context is captured at task creation
 * (on the originating thread), installed on the worker before the wrapped closure runs, and
 * cleared in {@code finally} so worker threads return to the pool with no identity. With no
 * context to propagate (e.g. system background threads), the wrapping is effectively a no-op.
 *
 * This is the Grails equivalent of Spring Boot's auto-configured context-propagating
 * {@code TaskExecutor} — necessary because Grails' {@code Promises.task} uses its own
 * internal executor that Spring Boot's auto-configuration does not wrap.
 */
@CompileStatic
class HoistPromiseFactory implements PromiseFactory {

    @Delegate PromiseFactory delegate

    HoistPromiseFactory(PromiseFactory delegate) {
        this.delegate = delegate
    }

    <T> Promise<T> createPromise(Closure<T>... closures) {
        delegate.createPromise(wrapClosures(closures as List<Closure<T>>) as Closure[]) as Promise<T>
    }

    <T> Promise<T> createPromise(Closure<T> closure, List decorators) {
        delegate.createPromise(wrapClosure(closure), decorators)
    }

    <T> Promise<List<T>> createPromise(List<Closure<T>> closures) {
        delegate.createPromise(wrapClosures(closures))
    }

    <T> Promise<List<T>> createPromise(List<Closure<T>> closures, List decorators) {
        delegate.createPromise(wrapClosures(closures), decorators)
    }

    <K, T> Promise<Map<K, T>> createPromise(Map<K, Closure<T>> promises) {
        delegate.createPromise(wrapPromises(promises)) as Promise<Map<K, T>>
    }

    <K, T> Promise<Map<K, T>> createPromise(Map<K, Closure<T>> promises, List decorators) {
        delegate.createPromise(wrapPromises(promises), decorators) as Promise<Map<K, T>>
    }

    //--------------------------
    // Implementation
    //--------------------------
    private <T> Closure<T> wrapClosure(Closure<T> closure) {
        def capturedIdentity = identityService.threadIdentity.get()
        def capturedContext = Context.current()
        return { ->
            identityService.installThreadIdentity(capturedIdentity)
            try (def scope = capturedContext.makeCurrent()) {
                return closure.call()
            } finally {
                identityService.installThreadIdentity(null)
            }
        } as Closure<T>
    }

    private <T> List<Closure<T>> wrapClosures(List<Closure<T>> closures) {
        closures.collect { wrapClosure(it) }
    }

    private <K, T> Map<K, Closure<T>> wrapPromises(Map<K, Closure<T>> promises) {
        promises.collectEntries { k, v -> [k, wrapClosure(v)] } as Map<K, Closure<T>>
    }
}
