/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.async.Promises
import grails.plugins.Plugin
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.cluster.InstanceState
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.util.Timer
import io.xh.hoist.util.Utils
import io.xh.hoist.websocket.HoistWebSocketConfigurer
import jakarta.servlet.DispatcherType
import org.grails.async.factory.future.CompletableFuturePromiseFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.Ordered
import org.springframework.web.context.WebApplicationContext

import static io.xh.hoist.util.Utils.createCustomOrDefault

class HoistCoreGrailsPlugin extends Plugin {

    def grailsVersion = '8.0.0 > *'
    def title = 'hoist-core'
    def author = 'Extremely Heavy'
    def authorEmail = 'info@xh.io'
    def description = 'Rapid Web Application Delivery System.'
    def profiles = ['rest-api']
    def documentation = 'https://github.com/xh/hoist-core/blob/master/README.md'
    def organization = [name: 'Extremely Heavy', url: 'https://xh.io']
    def scm = [url: 'https://github.com/xh/hoist-core']


    Closure doWithSpring() {
        {->
            Utils.grailsApplication = grailsApplication

            // Configure logging asap -- before this we rely on defaults in ApplicationConfig.groovy
            def logbackConfig = createCustomOrDefault(Utils.appPackage + '.LogbackConfig', LogbackConfig)
            logbackConfig.configure()

            ClusterService.initializeHazelcast()

            hoistFilter(FilterRegistrationBean) {
                filter = bean(HoistFilter)
                order = Ordered.HIGHEST_PRECEDENCE + 40
                dispatcherTypes = EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR)
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

    void doWithApplicationContext() {
        ensureServletVersion()
        Utils.appContext = applicationContext

        // Back `task {}` with an unbounded cached thread pool, as in Grails 7. Grails 8 defaults to
        // Spring's `applicationTaskExecutor` which deadlocks the nested blocking tasks we use
        Promises.promiseFactory = new HoistPromiseFactory(new CompletableFuturePromiseFactory())
    }

    // Grails 8 requires Servlet 6.1 (Tomcat 11).
    private void ensureServletVersion() {
        def ctx = (applicationContext as WebApplicationContext).servletContext
        int major = ctx.majorVersion, minor = ctx.minorVersion
        if (major < 6 || (major == 6 && minor < 1)) {
            throw new IllegalStateException(
                "Hoist requires Servlet 6.1+ (Tomcat 11+), but ${ctx.serverInfo} provides Servlet ${major}.${minor}."
            )
        }
    }

    void onConfigChange(Map<String, Object> event) {}

    void onShutdown(Map<String, Object> event) {
        // Orchestrate resource cleanup. This is *after* plugin and Bootstrap.destroy() have run
        ClusterService.instanceState = InstanceState.STOPPING
        Timer.shutdownAll()
        ClusterService.shutdownHazelcast()
    }
}
