/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.plugins.Plugin
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.util.Timer
import io.xh.hoist.websocket.HoistWebSocketConfigurer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.Ordered

class HoistCoreGrailsPlugin extends Plugin {

    def grailsVersion = '6.0.0 > *'
    def pluginExcludes = []

    def title = 'hoist-core'
    def author = 'Extremely Heavy'
    def authorEmail = 'info@xh.io'
    def description = 'Rapid Web Application Delivery System.'
    def profiles = ['web']

    // URL to the plugin's documentation
    def documentation = 'https://github.com/xh/hoist-core/blob/master/README.md'
    def organization = [name: 'Extremely Heavy', url: 'https://xh.io']
    def scm = [url: 'https://github.com/xh/hoist-core']
    def observe = ["services"]


    Closure doWithSpring() {
        {->
            ClusterService.initializeHazelcast()

            hoistFilter(FilterRegistrationBean) {
                filter = bean(HoistFilter)
                order = Ordered.HIGHEST_PRECEDENCE + 40
            }

            if (config.getProperty('hoist.enableWebSockets', Boolean)) {
                hoistWebSocketConfigurer(HoistWebSocketConfigurer)
            }

            xhExceptionHandler(ExceptionHandler)
        }
    }

    void doWithDynamicMethods() {
        // Workaround for issue with unconstrained findAll() and list().
        // See https://github.com/grails/gorm-hibernate5/issues/750
        grailsApplication.domainClasses.each {
            def meta = it.metaClass.static
            meta.list = meta.findAll = { delegate.findAll({}) }
            meta.list = meta.findAll = { Map params -> delegate.findAll(params, {}) }
        }
    }

    void doWithApplicationContext() {}

    void onConfigChange(Map<String, Object> event) {}

    void onShutdown(Map<String, Object> event) {
        // Orchestrate shutdown here. This is *after* all plugin and app Bootstrap.destroy() have run
        Timer.shutdownAll()
        ClusterService.shutdownHazelcast()
    }
}
