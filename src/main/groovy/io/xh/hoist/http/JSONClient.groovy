/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.http

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.xh.hoist.json.JSON
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.protocol.BasicHttpContext
import org.grails.web.json.JSONArray
import org.grails.web.json.JSONObject

import static org.apache.http.HttpStatus.SC_NO_CONTENT
import static org.apache.http.HttpStatus.SC_OK

@Slf4j
@CompileStatic
class JSONClient {

    private final CloseableHttpClient client

    JSONClient(CloseableHttpClient client) {
        this.client = client
    }

    //--------------------------------------------------
    // Convenience Methods for getting decoded response
    //---------------------------------------------------
    JSONObject executeAsJSONObject(HttpRequestBase method) {
        String ret = executeAsString(method)
        return ret ? (JSONObject) JSON.parse(ret) : null
    }

    JSONArray executeAsJSONArray(HttpRequestBase method) {
        String ret = executeAsString(method)
        return ret ? (JSONArray) JSON.parse(ret) : null
    }

    String executeAsString(HttpRequestBase method) {
        def response
        try {
            response = execute(method)
            def statusCode = response.statusLine.statusCode
            if (statusCode == SC_NO_CONTENT) return null
            return response.entity.content.getText()
        } finally {
            if (response) response.close()
        }
    }

    Integer executeAsStatusCode(HttpRequestBase method) {
        def response
        try {
            response = execute(method)
            return response.statusLine.statusCode
        } finally {
            if (response) response.close()
        }
    }

    
    //------------------------
    // Implementation
    //------------------------
    private CloseableHttpResponse execute(HttpRequestBase method) {
        CloseableHttpResponse ret = null
        Exception cause = null
        String statusText
        boolean success

        try {
            ret = executeRaw(client, method)
            Integer statusCode = ret.statusLine.statusCode
            success = (statusCode >= SC_OK  && statusCode <= SC_NO_CONTENT)
            statusText = statusCode.toString()
        } catch (Exception e) {
            cause = e
            statusText = e.message
            success = false
        }

        if (!success) {
            def exception = ret ? parseException(ret) : null
            if (!exception) {
                def msg = "Failure calling ${method.URI} : $statusText"
                exception = new RuntimeException(msg, cause)
            }
            if (ret) ret.close()
            throw exception
        }

        return ret
    }

    protected CloseableHttpResponse executeRaw(CloseableHttpClient client, HttpRequestBase method) {
        return client.execute(method, new BasicHttpContext())
    }

    // Attempt to parse a reasonable exception from failed response, but never actually throw if not possible.
    private Throwable parseException(CloseableHttpResponse response){
        def text = response.entity.content.getText()
        if (!text) return null
        try {
            def ex = (JSONObject) JSON.parse(text),
                className = ex.className,
                msg = ex.message

            // Message is required
            if (msg instanceof String) {

                // Try to rehydrate exception of certain known and present classes
                if (className instanceof String &&
                        (className.contains('io.xh.hoist') || className.contains('java.lang'))) {
                    def cls = this.class.classLoader.loadClass(className),
                        constructor = cls?.getConstructor(String)

                    if (constructor) {
                        return (Throwable) constructor.newInstance(msg)
                    }

                }
                // otherwise fall back to a runtime exception...
                return new RuntimeException(msg)
            }
        } catch (Exception ignored) {}

        return null
    }
    
}
