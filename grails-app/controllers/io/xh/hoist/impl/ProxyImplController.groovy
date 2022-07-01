/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.impl

import groovy.transform.CompileStatic
import io.xh.hoist.BaseController
import io.xh.hoist.http.BaseProxyService
import io.xh.hoist.security.AccessAll

@AccessAll
@CompileStatic
class ProxyImplController extends BaseController {

    def index(String name, String url) {
        String serviceName = name + 'Service'
        BaseProxyService proxy = (BaseProxyService) grailsApplication.mainContext[serviceName]

        if (!proxy) throw new RuntimeException("No Proxy '${name}' found")

        proxy.handleRequest(url, request, response)
    }
    
}
