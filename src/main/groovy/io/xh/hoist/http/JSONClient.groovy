/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.http

import groovy.transform.CompileStatic
import io.xh.hoist.exception.ExternalHttpException
import io.xh.hoist.json.JSONParser
import io.xh.hoist.util.StringUtils
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients

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
        def response = null
        try {
            response = execute(method)
            def statusCode = response.code
            if (statusCode == SC_NO_CONTENT) return null
            return JSONParser.parseObject(response.entity.content)
        } finally {
            response?.close()
        }
    }

    /**
     * Execute and parse a request expected to return an array of JSON objects.
     */
    List executeAsList(HttpUriRequestBase method) {
        def response = null
        try {
            response = execute(method)
            def statusCode = response.code
            if (statusCode == SC_NO_CONTENT) return null
            return JSONParser.parseArray(response.entity.content)
        } finally {
            response?.close()
        }
    }

    /**
     * Execute request and return raw content string.
     */
    String executeAsString(HttpUriRequestBase method) {
        def response = null
        try {
            response = execute(method)
            def statusCode = response.code
            if (statusCode == SC_NO_CONTENT) return null
            return response.entity.content.text
        } finally {
            response?.close()
        }
    }

    /**
     * Execute request and return status code only.
     */
    Integer executeAsStatusCode(HttpUriRequestBase method) {
        def response = null
        try {
            response = execute(method)
            return response.code
        } finally {
            response?.close()
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
            success = false
        }

        if (!success) {
            ret?.close()
            throw new ExternalHttpException("Failure calling ${method.uri} : $statusText", cause, statusCode)
        }

        return ret
    }

    protected CloseableHttpResponse executeRaw(CloseableHttpClient client, HttpUriRequestBase method) {
        return client.execute(method)
    }

    // Attempt to parse a reasonable exception from failed response, but never actually throw if not possible.
    private Throwable parseException(CloseableHttpResponse response) {
        try {
            String content = response.entity.content?.getText()?.trim()
            if (!content) return null

            // [1] We have a valid json object (preferred)
            Map obj = safeParseObject(content)
            if (obj) {
                String msg = obj.message instanceof String ? obj.message : 'Unknown Error'

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
            return new RuntimeException(StringUtils.elide(content, 255))
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

}
