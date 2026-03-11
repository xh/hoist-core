/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.cluster

import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import io.xh.hoist.BaseService
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.Utils
import java.util.concurrent.Callable

import static io.xh.hoist.util.Utils.getIdentityService
import static io.xh.hoist.util.Utils.getClusterService
import static io.xh.hoist.util.Utils.getTraceService
import static io.xh.hoist.util.Utils.getAppContext
import static io.xh.hoist.json.JSONSerializer.serialize
import static java.util.Collections.emptySet

/**
 * Serializable task for executing remote service calls across the cluster.
 *
 * Includes support for trampolining verified user identity to remote server, and
 * structured exception handling.
 */
class ClusterTask implements Callable<ClusterResult>, LogSupport {

    final String svc
    final String method
    final List args
    final String username
    final String authUsername
    final boolean asJson
    final String traceparent


    ClusterTask(BaseService svc, String method, List args, boolean asJson) {
        this.svc = svc.class.name
        this.method = method
        this.args = args ?: []
        this.asJson = asJson
        username = identityService.username
        authUsername = identityService.authUsername
        traceparent = captureTraceparent()
    }

    ClusterResult call() {
        identityService.threadUsername.set(username)
        identityService.threadAuthUsername.set(authUsername)

        def traceScope = restoreTraceContext()
        try {
            clusterService.ensureRunning()

            def doCall = {
                def clazz = Class.forName(svc),
                    service = appContext.getBean(clazz),
                    value = service.invokeMethod(method, args.toArray()),
                    valueIsVoid = value !== null ? false : clazz.methods.find{it.name == method}?.returnType == Void.TYPE

                return new ClusterResult(value: asJson && !valueIsVoid ? serialize(value) : value)
            }

            if (traceScope) {
                def spanName = "ClusterTask ${svc.substring(svc.lastIndexOf('.') + 1)}.${method}".toString()
                return (ClusterResult) traceService.withSpan(spanName, [source: 'hoist'], doCall)
            } else {
                return (ClusterResult) doCall.call()
            }

        } catch (Throwable t) {
            Utils.handleException(
                exception: t,
                logTo: this,
                logMessage: [_action: this.class.simpleName]
            )
            return new ClusterResult(exception: new ClusterTaskException(t))
        } finally {
            traceScope?.close()
            identityService.threadUsername.set(null)
            identityService.threadAuthUsername.set(null)
        }
    }

    private String captureTraceparent() {
        if (!traceService.enabled) return null
        Map<String, String> carrier = [:]
        traceService.otelSdk.propagators.textMapPropagator
            .inject(Context.current(), carrier, MAP_SETTER)
        carrier.traceparent
    }

    private Object restoreTraceContext() {
        if (!traceparent || !traceService.enabled) return null
        Map<String, String> carrier = [traceparent: traceparent]
        def context = traceService.otelSdk.propagators.textMapPropagator
            .extract(Context.current(), carrier, MAP_GETTER)
        context.makeCurrent()
    }

    private final TextMapSetter<Map<String, String>> MAP_SETTER = new TextMapSetter<Map<String, String>>() {
        void set(Map<String, String> carrier, String key, String value) {
            carrier?.put(key, value)
        }
    }

    private final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<Map<String, String>>() {
        Iterable<String> keys(Map<String, String> carrier) {
            carrier?.keySet() ?: emptySet()
        }
        String get(Map<String, String> carrier, String key) {
            carrier?.get(key)
        }
    }
}
