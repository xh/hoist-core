/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.http

import groovy.transform.CompileStatic
import groovy.transform.NamedVariant
import io.xh.hoist.exception.ExternalHttpException
import io.xh.hoist.telemetry.trace.SpanRef
import io.xh.hoist.json.JSONParser
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients

import static io.opentelemetry.api.trace.SpanKind.CLIENT
import static io.xh.hoist.telemetry.ObservedRun.observe
import static io.xh.hoist.util.StringUtils.elide
import static io.xh.hoist.util.Utils.traceContextService
import static org.apache.hc.core5.http.HttpStatus.SC_GATEWAY_TIMEOUT
import static org.apache.hc.core5.http.HttpStatus.SC_NO_CONTENT
import static org.apache.hc.core5.http.HttpStatus.SC_OK

/**
 * A thin wrapper around an Apache HttpClient to execute HTTP requests and return parsed
 * java objects representing the JSON content.  This object can also return responses as
 * text or an int HTTP status code.
 *
 * This wrapper also provides additional customizations around exceptions. If a response returns
 * with an HTTP status code indicating an error (i.e. *not* 200-204), the execute method will throw.
 *
 * Supports a single, simple {@link #timeoutMs} that bounds the total duration of
 * any request executed by this client. This sidesteps Apache HttpClient's
 * per-phase timeout knobs (`connect` / `connectionRequest` / `response`), which do not provide
 * an end-to-end deadline and have known gaps for proxy CONNECT, TLS handshake, and slow-trickle
 * scenarios. See xh/hoist-core#241.
 */
@CompileStatic
class JSONClient {

    private final CloseableHttpClient _client

    /**
     * Timeout (ms) applied to every request issued by this client. Null
     * (the default) disables the deadline. When exceeded, the in-flight request is forcibly
     * aborted and a 504 {@link ExternalHttpException} is thrown.
     */
    final Long timeoutMs

    /**
     * Construct this object.
     *
     * @param client - a preconfigured HttpClient instance, or omit for a default client.
     * @param timeoutMs - timeout applied to every request, or null to disable.
     */
    @NamedVariant
    JSONClient(CloseableHttpClient client = HttpClients.createDefault(), Long timeoutMs = null) {
        this._client = client
        this.timeoutMs = timeoutMs
    }

    //--------------------------------------------------
    // Convenience Methods for getting decoded response
    //---------------------------------------------------
    /**
     * Execute and parse a request expected to return a single JSON object.
     * @param timeoutMs - per-call timeout override; defaults to this client's timeoutMs.
     */
    Map executeAsMap(HttpUriRequestBase method, Long timeoutMs = this.timeoutMs) {
        try (AbortHandle abort = scheduleAbort(method, timeoutMs)
             CloseableHttpResponse response = execute(method, abort, timeoutMs)) {
            return response.code == SC_NO_CONTENT
                ? null
                : JSONParser.parseObject(response.entity.content)
        }
    }

    /**
     * Execute and parse a request expected to return an array of JSON objects.
     * @param timeoutMs - per-call timeout override; defaults to this client's timeoutMs.
     */
    List executeAsList(HttpUriRequestBase method, Long timeoutMs = this.timeoutMs) {
        try (
            AbortHandle abort = scheduleAbort(method, timeoutMs)
            CloseableHttpResponse response = execute(method, abort, timeoutMs)
        ) {
            return response.code == SC_NO_CONTENT
                ? null
                : JSONParser.parseArray(response.entity.content)
        }
    }

    /**
     * Execute request and return raw content string.
     * @param timeoutMs - per-call timeout override; defaults to this client's timeoutMs.
     */
    String executeAsString(HttpUriRequestBase method, Long timeoutMs = this.timeoutMs) {
        try (
            AbortHandle abort = scheduleAbort(method, timeoutMs)
            CloseableHttpResponse response = execute(method, abort, timeoutMs)
        ) {
            return response.code == SC_NO_CONTENT ? null : response.entity.content.text
        }
    }

    /**
     * Execute request and return status code only.
     * @param timeoutMs - per-call timeout override; defaults to this client's timeoutMs.
     */
    Integer executeAsStatusCode(HttpUriRequestBase method, Long timeoutMs = this.timeoutMs) {
        try (
            AbortHandle abort = scheduleAbort(method, timeoutMs)
            CloseableHttpResponse response = execute(method, abort, timeoutMs)
        ) {
            return response.code
        }
    }


    //------------------------
    // Implementation
    //------------------------
    private CloseableHttpResponse execute(HttpUriRequestBase method, AbortHandle abort, Long timeoutMs) {
        CloseableHttpResponse ret = null
        Throwable cause = null
        Integer statusCode = null
        String statusText = ''
        boolean success

        if (!method.containsHeader('Content-type') && method.method in ['POST', 'PUT', 'PATCH']) {
            method.setHeader('Content-type', 'application/json')
        }

        if (!method.containsHeader('Accept')) {
            method.setHeader('Accept', 'application/json')
        }

        try {
            ret = executeRaw(_client, method)
            statusCode = ret.code
            success = (statusCode >= SC_OK  && statusCode <= SC_NO_CONTENT)
            if (!success) {
                cause = parseException(ret)
                statusText = statusCode.toString()
            }
        } catch (Throwable e) {
            if (abort.fired) {
                statusCode = SC_GATEWAY_TIMEOUT
                statusText = "Aborted after exceeding ${timeoutMs}ms timeout"
            } else {
                cause = e
                statusText = e.message
            }
            success = false
        }

        if (!success) {
            ret?.close()
            throw new ExternalHttpException("Failure calling ${method.uri} : $statusText", cause, statusCode)
        }

        return ret
    }

    protected CloseableHttpResponse executeRaw(CloseableHttpClient client, HttpUriRequestBase method) {
        observe(this)
            .span(
                name: method.method,
                kind: CLIENT,
                tags: [
                    'http.request.method': method.method,
                    'url.full'           : method.uri,
                    'server.address'     : method.uri.host,
                    'server.port'        : method.uri.port > 0 ? method.uri.port : null,
                    'xh.source'          : 'hoist'
                ]
            )
            .run { SpanRef span ->
                traceContextService.injectContext(method)
                def ret = client.execute(method)
                span.setHttpStatusAndErrorStatus(ret.code)
                ret
            }
    }

    // Attempt to parse a reasonable exception from failed response, but never actually throw if not possible.
    private Throwable parseException(CloseableHttpResponse response) {
        try {
            String content = response.entity.content?.getText()?.trim()
            if (!content) return null

            // [1] We have a valid json object (preferred)
            Map obj = safeParseObject(content)
            int MAX_MSG_LEN = 255
            if (obj) {
                String msg = obj.message instanceof String ? obj.message : content
                msg = elide(msg, MAX_MSG_LEN)

                // Try to rehydrate exception of certain known and present classes
                def className = obj.className
                if (className instanceof String &&
                    (className.contains('io.xh.hoist') || className.contains('java.lang'))) {
                    def cls = this.class.classLoader.loadClass(className),
                        constructor = cls?.getConstructor(String)
                    if (constructor) {
                        return (Throwable) constructor.newInstance(msg)
                    }
                }
                // otherwise use a runtime exception.
                return new RuntimeException(msg)
            }

            // [2] Or just interpret content as a raw string message
            return new RuntimeException(elide(content, MAX_MSG_LEN))
        } catch (Exception ignored) {
            // [3] ..but in the worst case, just return null
            return null
        }
    }

    private Map safeParseObject(String s) {
        try {
            return JSONParser.parseObject(s)
        } catch (Exception ignored) {
            return null
        }
    }

    //------------------------
    // Timeout abort
    //------------------------
    private static final java.util.Timer ABORT_TIMER = new java.util.Timer('JSONClientAbort', true)

    private static AbortHandle scheduleAbort(HttpUriRequestBase method, Long timeoutMs) {
        AbortHandle handle = new AbortHandle()
        if (!timeoutMs || timeoutMs <= 0) return handle
        handle.task = new TimerTask() {
            void run() {
                handle.fired = true
                try { method.abort() } catch (Throwable ignored) {}
            }
        }
        ABORT_TIMER.schedule(handle.task, timeoutMs)
        return handle
    }

    private static class AbortHandle implements AutoCloseable {
        volatile boolean fired = false
        TimerTask task

        void close() {
            task?.cancel()
        }
    }
}
