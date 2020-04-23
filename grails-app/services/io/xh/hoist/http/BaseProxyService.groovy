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
import org.apache.http.HttpResponse
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPatch
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.message.BasicNameValuePair
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

        HttpRequestBase method = null
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
            response.setStatus(sourceResponse.statusLine.statusCode)
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
    protected void installRequestHeaders(HttpServletRequest request, HttpRequestBase method) {
        def names = proxyRequestHeaders(),
            send = (Collection<String>) request.headerNames.findAll { hasHeader(names, (String) it) }

        send.each { method.setHeader(it, request.getHeader(it)) }
    }

    protected void installResponseHeaders(HttpServletResponse response, HttpResponse sourceResponse) {
        def names = proxyResponseHeaders(),
            send = sourceResponse.allHeaders.findAll {hasHeader(names, it.name)}

        send.each {
            response.setHeader(it.name, it.value)
        }
    }

    protected installParamsOnEntity(HttpServletRequest request, HttpEntityEnclosingRequestBase method) {
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
