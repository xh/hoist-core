/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.user

import grails.async.Promise
import grails.async.PromiseFactory
import groovy.transform.CompileStatic

/**
 * Delegating {@link PromiseFactory} that propagates the originating thread's
 * {@link HoistIdentity} to worker threads spawned by Grails {@code task {}} calls.
 *
 * Installed once at startup by {@link IdentityService}. Captures identity at task creation
 * time (still on the originating thread) and installs it on the worker before the wrapped
 * closure runs, then restores the prior value in a {@code finally}. With no identity to
 * propagate (e.g. system background threads), the wrapping is a no-op.
 *
 * Composes with {@link io.xh.hoist.telemetry.trace.ContextPropagatingPromiseFactory}: both
 * decorate the underlying factory, each propagating its own slice of context.
 */
@CompileStatic
class IdentityPropagatingPromiseFactory implements PromiseFactory {

    @Delegate PromiseFactory delegate
    private final IdentityService identityService

    IdentityPropagatingPromiseFactory(PromiseFactory delegate, IdentityService identityService) {
        this.delegate = delegate
        this.identityService = identityService
    }

    @Override
    <T> Promise<T> createPromise(Closure<T>... closures) {
        delegate.createPromise(wrapClosures(closures as List<Closure<T>>) as Closure[]) as Promise<T>
    }

    @Override
    <T> Promise<T> createPromise(Closure<T> closure, List decorators) {
        delegate.createPromise(wrapClosure(closure), decorators)
    }

    @Override
    <T> Promise<List<T>> createPromise(List<Closure<T>> closures) {
        delegate.createPromise(wrapClosures(closures))
    }

    @Override
    <T> Promise<List<T>> createPromise(List<Closure<T>> closures, List decorators) {
        delegate.createPromise(wrapClosures(closures), decorators)
    }

    @Override
    <K, T> Promise<Map<K, T>> createPromise(Map<K, Closure<T>> promises) {
        delegate.createPromise(wrapPromises(promises)) as Promise<Map<K, T>>
    }

    @Override
    <K, T> Promise<Map<K, T>> createPromise(Map<K, Closure<T>> promises, List decorators) {
        delegate.createPromise(wrapPromises(promises), decorators) as Promise<Map<K, T>>
    }

    //--------------------------
    // Implementation
    //--------------------------
    private <T> Closure<T> wrapClosure(Closure<T> closure) {
        def captured = identityService.threadIdentity.get()
        return { ->
            def prior = identityService.threadIdentity.get()
            if (captured != null) {
                identityService.threadIdentity.set(captured)
            } else {
                identityService.threadIdentity.remove()
            }
            try {
                return closure.call()
            } finally {
                if (prior != null) {
                    identityService.threadIdentity.set(prior)
                } else {
                    identityService.threadIdentity.remove()
                }
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
