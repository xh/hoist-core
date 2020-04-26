/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.http

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import org.apache.catalina.connector.ClientAbortException
import org.apache.hc.core5.http.HttpResponse
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse
import org.apache.hc.client5.http.classic.methods.HttpDelete
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpPatch
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.classic.methods.HttpPut
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.core5.http.io.entity.StringEntity
import org.apache.hc.core5.http.message.BasicNameValuePair
import groovy.util.logging.Slf4j

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Slf4j
@CompileStatic
abstract class BaseProxyService extends BaseService {

    protected CloseableHttpClient _sourceClient

    //------------------------------------------------------
    // Main Entry points for subclasses
    //------------------------------------------------------
    protected abstract CloseableHttpClient createSourceClient()

    protected String getSourceRoot()                {return ''}
    protected List<String> proxyRequestHeaders()    {return []}
    protected List<String> proxyResponseHeaders()   {return []}


    void handleRequest(String endpoint, HttpServletRequest request, HttpServletResponse response) {
        def queryStr = request.queryString ? '?' + request.queryString : '',
            cleanEndpoint = endpoint.replaceAll(/ /, '%20'),
            fullPath = sourceRoot + '/' + cleanEndpoint + queryStr

        HttpUriRequestBase method = null
        switch (request.getMethod()) {
            case 'DELETE':
                method = new HttpDelete(fullPath)
                break
            case 'GET':
                method = new HttpGet(fullPath)
                break
            case 'PATCH':
                method = new HttpPatch(fullPath)
                installParamsOnEntity(request, (HttpPatch) method)
                break
            case 'POST':
                method = new HttpPost(fullPath)
                installParamsOnEntity(request, (HttpPost) method)
                break
            case 'PUT':
                method = new HttpPut(fullPath)
                installParamsOnEntity(request, (HttpPut) method)
                break
            default:
                throw new RuntimeException('Unsupported HTTP method')
        }
        installRequestHeaders(request, method)

        CloseableHttpResponse sourceResponse
        try {
            CloseableHttpClient source = sourceClient
            sourceResponse = source.execute(method)
            response.setStatus(sourceResponse.code)
            installResponseHeaders(response, sourceResponse)
            sourceResponse.entity.writeTo(response.outputStream)
            response.flushBuffer()
        } catch (ClientAbortException ignored) {
            log.debug("Client has aborted request to [$endpoint] - ignoring")
        } finally {
            if (sourceResponse) sourceResponse.close()
        }
    }

    //------------------------------------------------
    // Additional overrideable implementation methods
    //------------------------------------------------
    protected void installRequestHeaders(HttpServletRequest request, HttpUriRequestBase method) {
        def names = proxyRequestHeaders(),
            send = (Collection<String>) request.headerNames.findAll { hasHeader(names, (String) it) }

        send.each { method.setHeader(it, request.getHeader(it)) }
    }

    protected void installResponseHeaders(HttpServletResponse response, HttpResponse sourceResponse) {
        def names = proxyResponseHeaders(),
            send = sourceResponse.headers.findAll {hasHeader(names, it.name)}

        send.each {
            response.setHeader(it.name, it.value)
        }
    }

    protected installParamsOnEntity(HttpServletRequest request, HttpUriRequestBase method) {
        if (request.getHeader('Content-Type').toLowerCase().contains('x-www-form-urlencoded')) {
            def formParams = request.parameterMap.collectMany {key, value ->
                value.collect {new BasicNameValuePair(key, (String) it)}
            }
            method.setEntity(new UrlEncodedFormEntity(formParams))
        } else {
            def body = request.reader.text
            method.setEntity(new StringEntity(body))
        }
    }

    protected CloseableHttpClient getSourceClient() {
        def ret = _sourceClient
        if (!ret) ret = createSourceClient()
        return ret
    }

    protected boolean hasHeader(List<String> headers, String str) {
        headers.any {it.equalsIgnoreCase(str)}
    }

    void clearCaches() {
        _sourceClient = null
        super.clearCaches()
    }
    
}
