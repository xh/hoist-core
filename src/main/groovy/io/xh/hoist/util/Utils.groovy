/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import grails.config.Config
import grails.core.GrailsApplication
import grails.util.Environment
import grails.util.Holders
import grails.util.Metadata
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.xh.hoist.AppEnvironment
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.entra.EntraIdService
import io.xh.hoist.environment.EnvironmentService
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.ldap.LdapService
import io.xh.hoist.log.LogLevelService
import io.xh.hoist.log.LogSupport
import io.xh.hoist.pref.PrefService
import io.xh.hoist.role.BaseRoleService
import io.xh.hoist.security.BaseAuthenticationService
import io.xh.hoist.user.BaseUserService
import io.xh.hoist.user.IdentityService
import io.xh.hoist.telemetry.trace.TraceContextService
import io.xh.hoist.telemetry.trace.TraceService
import io.xh.hoist.websocket.WebSocketService
import org.grails.web.servlet.mvc.GrailsWebRequest

import org.springframework.context.ApplicationContext
import org.springframework.web.context.request.RequestContextHolder

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import javax.sql.DataSource

class Utils {

    //------------------
    // App Identifiers
    //------------------
    /** Internal short name of the application - lowercase, no spaces. */
    static String getAppCode() {
        return Metadata.current.getProperty('info.xh.appCode', String, null)
    }

    /** Internal package name of the application - lowercase, no spaces. */
    static String getAppPackage() {
        return Metadata.current.getProperty('info.xh.appPackage', String, null)
    }

    /** User-facing display name of the application - proper case, can include spaces. */
    static String getAppName() {
        return Metadata.current.getProperty('info.xh.appName', String, null)
    }

    /** Current version, either SemVer x.y.z format or x.y-SNAPSHOT. */
    static String getAppVersion() {
        return Metadata.current.getProperty('info.app.version', String, null)
    }

    /** Git commit hash or other identifier set at build time. */
    static String getAppBuild() {
        return Metadata.current.getProperty('info.xh.appBuild', String, null)
    }

    /** Version of hoist-react installed with the application, set at build time. */
    static String getHoistReactVersion() {
        return Metadata.current.getProperty('info.xh.hoistReactVersion', String, null)
    }

    /** Version of hoist-core installed with the application. */
    static String getHoistCoreVersion() {
        return Holders.currentPluginManager().getGrailsPlugin('hoist-core')?.version
    }

    /** Hoist AppEnvironment of the current deployment, distinct from Grails environment. */
    static AppEnvironment getAppEnvironment() {
        return InstanceConfigUtils.appEnvironment
    }

    static Boolean getIsProduction() {
        return appEnvironment == AppEnvironment.PRODUCTION
    }

    /** True if app is running in local development mode, regardless of AppEnvironment. */
    static Boolean getIsLocalDevelopment() {
        return Environment.isDevelopmentMode()
    }


    //------------------
    // Service Accessors
    //------------------
    /** All singleton instances of io.xh.BaseService in the application */
    static List<BaseService> getXhServices() {
        return appContext.getBeansOfType(BaseService, false, true).collect { it.value }
    }

    static BaseAuthenticationService getAuthenticationService() {
        return (BaseAuthenticationService) appContext.authenticationService
    }

    static BaseRoleService getRoleService() {
        return (BaseRoleService) appContext.roleService
    }

    static BaseUserService getUserService() {
        return (BaseUserService) appContext.userService
    }

    static ClusterService getClusterService() {
        return (ClusterService) appContext.clusterService
    }

    static ConfigService getConfigService() {
        return (ConfigService) appContext.configService
    }

    static EntraIdService getEntraIdService() {
        return (EntraIdService) appContext.entraIdService
    }

    static EnvironmentService getEnvironmentService() {
        return (EnvironmentService) appContext.environmentService
    }

    static IdentityService getIdentityService() {
        return (IdentityService) appContext.identityService
    }

    static LdapService getLdapService() {
        return (LdapService) appContext.ldapService
    }

    static LogLevelService getLogLevelService() {
        return (LogLevelService) appContext.logLevelService
    }

    static PrefService getPrefService() {
        return (PrefService) appContext.prefService
    }

    static TraceService getTraceService() {
        return (TraceService) appContext.traceService
    }

    static TraceContextService getTraceContextService() {
        return (TraceContextService) appContext.traceContextService
    }

    static WebSocketService getWebSocketService() {
        return (WebSocketService) appContext.webSocketService
    }


    //------------------
    // Other Singletons
    //------------------
    /**
     * Hoist's own holders for the core Grails/Spring singletons.
     *
     * Grails populates its own `Holders` late in the boot sequence - both `Holders.grailsApplication`
     * and `Holders.applicationContext` throw during `doWithSpring`, which is where Hoist initializes
     * Hazelcast (and therefore needs to walk domain/service classes). HoistCoreGrailsPlugin installs
     * each of these as soon as it becomes available, and they are the single source of truth
     * thereafter - `Holders` is deliberately not consulted as a fallback, so a missing holder fails
     * loudly here rather than surfacing as a confusing error from deeper in Grails.
     */
    private static GrailsApplication _grailsApplication
    private static ApplicationContext _appContext

    /**
     * Set by HoistCoreGrailsPlugin.doWithSpring() - the earliest point a GrailsApplication exists.
     * @internal
     */
    static void setGrailsApplication(GrailsApplication grailsApplication) {
        _grailsApplication = grailsApplication
    }

    /** Get the grails application. */
    static GrailsApplication getGrailsApplication() {
        if (!_grailsApplication) {
            throw new IllegalStateException(
                'Utils.grailsApplication not yet available - it is installed by HoistCoreGrailsPlugin.doWithSpring().'
            )
        }
        return _grailsApplication
    }

    /**
     * Set by HoistCoreGrailsPlugin.doWithApplicationContext() - the ApplicationContext does not yet
     * exist during doWithSpring(), so this necessarily lands one phase later than the above.
     * @internal
     */
    static void setAppContext(ApplicationContext appContext) {
        _appContext = appContext
    }

    /** Get the grails application context.  */
    static ApplicationContext getAppContext() {
        if (!_appContext) {
            throw new IllegalStateException(
                'Utils.appContext not yet available - it is installed by HoistCoreGrailsPlugin.doWithApplicationContext(), one phase after doWithSpring().'
            )
        }
        return _appContext
    }

    /** Get the grails application config.  */
    static Config getGrailsConfig() {
        grailsApplication.config
    }

    /** Primary JDBC datasource, default backing DB for app Domain objects. */
    static DataSource getDataSource() {
        return (DataSource) appContext.dataSource
    }

    /** Primary dataSource configuration. */
    static Map getDataSourceConfig() {
        grailsConfig.getProperty('dataSource', Map.class).collectEntries { it }
    }

    static ExceptionHandler getExceptionHandler() {
        return (ExceptionHandler) appContext.xhExceptionHandler
    }


    static <T> T createCustomOrDefault(String customClassName, Class<T> clazz) {
        try {
            def customClass = Class.forName(customClassName)
            return customClass.getConstructor().newInstance() as T
        } catch (ClassNotFoundException e) {
            return clazz.getConstructor().newInstance()
        }
    }


    /**
     * Sanitizes, pre-processes, and logs exception.
     *
     * Used by BaseController, ClusterRequest, Timer, and HoistInterceptor to handle
     * otherwise unhandled exception.
     *
     *  @see ExceptionHandler, which may be overridden to customize this behavior.
     */
    @NamedVariant
    static void handleException(
        @NamedParam(required = true) Throwable exception,
        @NamedParam HttpServletResponse renderTo,
        @NamedParam LogSupport logTo,
        @NamedParam Object logMessage
    ) {
        try {
            exceptionHandler.handleException(exception, renderTo, logTo, logMessage)
        } catch (Throwable t) {
            // Backup -- don't muddy waters if handler fails or not available (e.g. during shutdown)
            if (logTo) {
                logTo.instanceLog.error(exception.message ?: 'Unknown Exception')
            }
            if (renderTo) {
                renderTo.setStatus(500)
                renderTo.setContentType('application/json')
                renderTo.flushBuffer()
            }
        }
    }


    //------------------
    // Validation/Parsing
    //------------------
    /** True if a String represents valid JSON. */
    static boolean isJSON(String val) {
        JSONParser.validate(val)
    }

    /**
     * True if the given parameter name is likely sensitive and should not be serialized.
     * To customize, set `hoist.sensitiveParamTerms` within your app's `application.groovy` to a
     * list of terms that should trigger this behavior.
     *
     * See {@link io.xh.hoist.configuration.ApplicationConfig} for the default list.
     */
    static boolean isSensitiveParamName(String name) {
        sensitiveParams.any { name.containsIgnoreCase(it) }
    }

    /** String parsing for a boolean. */
    static Boolean parseBooleanStrict(String s) {
        if ('true'.equalsIgnoreCase(s)) return true
        if ('false'.equalsIgnoreCase(s)) return false
        throw new RuntimeException('Unable to parse boolean value')
    }

    /**
     * Output the Json format of an object, with the values for any "sensitive" param
     * names redacted.
     */
    static Map asSanitizedJSON(Object obj) {
        def map = JSONParser.parseObject(JSONSerializer.serialize(obj))
        deepSanitizeMap(map)
    }

    //------------------
    // Misc/Other
    //------------------
    /**
     * Get the current request, or null if caller is not executing in the context of an HTTP request
     * (e.g. a service Timer or other async code).
     */
    static HttpServletRequest getCurrentRequest() {
        def attr = RequestContextHolder.requestAttributes

        return attr instanceof GrailsWebRequest ? attr.request : null
    }

    /**
     * Execute a closure with a given delegate. Useful for applying configuration to a script.
     * @see io.xh.hoist.configuration.ApplicationConfig
     */
    static void withDelegate(Object o, Closure c) {
        c.delegate = o
        c.call()
    }

    //----------------------
    // Implementation
    //------------------------
    private static terms = null
    private static List<String> getSensitiveParams() {
        if (terms == null) {
            terms = grailsConfig.getProperty('hoist.sensitiveParamTerms', ArrayList.class) as List<String>
        }
        return terms
    }

    private static Map deepSanitizeMap(Map map) {
        map.collectEntries {key, val ->
            if (isSensitiveParamName(key.toString())) {
                val = '******'
            } else if (val instanceof Map) {
                val = deepSanitizeMap(val)
            }
            return [key, val]
        }
    }
}
