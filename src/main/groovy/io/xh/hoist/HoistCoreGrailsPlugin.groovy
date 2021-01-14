/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.plugins.Plugin
import io.xh.hoist.exception.ExceptionRenderer
import io.xh.hoist.security.HoistSecurityFilter
import io.xh.hoist.util.Utils
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.Ordered

class HoistCoreGrailsPlugin extends Plugin {

    def grailsVersion = '4.0.6 > *'
    def pluginExcludes = []

    def title = 'hoist-core'
    def author = 'Extremely Heavy'
    def authorEmail = 'info@xh.io'
    def description = 'Rapid Web Application Delivery System.'
    def profiles = ['web']

    // URL to the plugin's documentation
    def documentation = 'https://github.com/xh/hoist-core/blob/master/README.md'
    def organization = [name: 'Extremely Heavy', url: 'http://xh.io']
    def scm = [url: 'https://github.com/xh/hoist-core']
    def observe = ["services"]


    Closure doWithSpring() {
        {->
            hoistIdentityFilter(FilterRegistrationBean) {
                filter = bean(HoistSecurityFilter)
                order = Ordered.HIGHEST_PRECEDENCE + 40
            }

            exceptionRenderer(ExceptionRenderer)
        }
    }

    void doWithDynamicMethods() {}

    void doWithApplicationContext() {}

    void onChange(Map<String, Object> event) {
        def cls = event.source
        if (cls instanceof Class) {
            def svcs = Utils.appContext
                    .getBeansOfType(BaseService)
                    .values()
                    .findAll {!it.initialized}
            BaseService.parallelInit(svcs)
        }
    }

    void onConfigChange(Map<String, Object> event) {}

    void onShutdown(Map<String, Object> event) {}
    
}
