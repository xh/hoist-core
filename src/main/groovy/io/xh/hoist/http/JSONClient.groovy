/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.http

import groovy.transform.CompileStatic
import io.xh.hoist.exception.ExternalHttpException
import io.xh.hoist.telemetry.trace.SpanRef
import io.xh.hoist.json.JSONParser
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.util.Timeout

import static io.opentelemetry.api.trace.SpanKind.CLIENT
import static io.xh.hoist.telemetry.ObservedRun.observe
import static io.xh.hoist.util.DateTimeUtils.SECONDS
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
 */
@CompileStatic
class JSONClient {

    private final CloseableHttpClient _client

    /**
     * @param client - a preconfigured HttpClient instance, or omit for a default client.
     */
    JSONClient(CloseableHttpClient client = HttpClients.createDefault()) {
        this._client = client
    }

    //--------------------------------------------------
    // Convenience Methods for getting decoded response
    //---------------------------------------------------
    /**
     * Execute and parse a request expected to return a single JSON object.
     */
    Map executeAsMap(HttpUriRequestBase method) {
        TimerTask abortTask = getAbortTask(method)
        try (CloseableHttpResponse response = execute(method)) {
            return response.code == SC_NO_CONTENT
                ? null
                : JSONParser.parseObject(response.entity.content)
        } finally {
            abortTask?.cancel()
        }
    }

    /**
     * Execute and parse a request expected to return an array of JSON objects.
     */
    List executeAsList(HttpUriRequestBase method) {
        TimerTask abortTask = getAbortTask(method)
        try (CloseableHttpResponse response = execute(method)) {
            return response.code == SC_NO_CONTENT
                ? null
                : JSONParser.parseArray(response.entity.content)
        } finally {
            abortTask?.cancel()
        }
    }

    /**
     * Execute request and return raw content string.
     */
    String executeAsString(HttpUriRequestBase method) {
        TimerTask abortTask = getAbortTask(method)
        try (CloseableHttpResponse response = execute(method)) {
            return response.code == SC_NO_CONTENT ? null : response.entity.content.text
        } finally {
            abortTask?.cancel()
        }
    }

    /**
     * Execute request and return status code only.
     */
    Integer executeAsStatusCode(HttpUriRequestBase method) {
        TimerTask abortTask = getAbortTask(method)
        try (CloseableHttpResponse response = execute(method)) {
            return response.code
        } finally {
            abortTask?.cancel()
        }
    }


    //------------------------
    // Implementation
    //------------------------
    private CloseableHttpResponse execute(HttpUriRequestBase method) {
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
            cause = e
            statusText = e.message
            if (method.aborted) {
                statusCode = SC_GATEWAY_TIMEOUT
                statusText = 'aborted after exceeding configured timeout'
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
    // Hard abort backstop
    //------------------------
    // Apache HttpClient's RequestConfig timeouts (connect / response / connectionRequest) do not
    // always reliably abort an in-flight request. We schedule an explicit `abort()` as
    // a backstop so request threads are reliably released. See xh/hoist-core#241.
    private static final java.util.Timer ABORT_TIMER = new java.util.Timer('JSONClientAbort', true)

    private static TimerTask getAbortTask(HttpUriRequestBase method) {
        // Only act when the caller has bounded the response phase
        RequestConfig config = method.config
        long responseMs = timeoutMs(config?.responseTimeout)
        if (responseMs <= 0) return null

        // Add headroom for connect time + grace period so HttpClient's own timeout machinery can fire
        long deadlineMs = responseMs + timeoutMs(config.connectionRequestTimeout) + 5 * SECONDS
        TimerTask task = new TimerTask() {
            void run() {
                try { method.abort() } catch (Throwable ignored) {}
            }
        }
        ABORT_TIMER.schedule(task, deadlineMs)
        return task
    }

    private static long timeoutMs(Timeout t) {
        t?.toMilliseconds() ?: 0L
    }

}
