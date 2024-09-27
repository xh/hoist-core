/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.JavaSerializer
import grails.config.Config
import grails.util.Environment
import grails.util.Holders
import grails.util.Metadata
import io.xh.hoist.AppEnvironment
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.environment.EnvironmentService
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.json.JSONParser
import io.xh.hoist.ldap.LdapService
import io.xh.hoist.log.LogSupport
import io.xh.hoist.pref.PrefService
import io.xh.hoist.role.BaseRoleService
import io.xh.hoist.security.BaseAuthenticationService
import io.xh.hoist.user.BaseUserService
import io.xh.hoist.user.IdentityService
import io.xh.hoist.websocket.WebSocketService
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.springframework.context.ApplicationContext
import org.springframework.web.context.request.RequestContextHolder

import javax.servlet.http.HttpServletRequest
import javax.sql.DataSource
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

import static java.lang.System.currentTimeMillis

class Utils {

    //------------------
    // App Identifiers
    //------------------
    /** Internal short name of the application - lowercase, no spaces. */
    static String getAppCode() {
        return Metadata.current.getProperty('info.xh.appCode', String).orElse(null)
    }

    /** Internal package name of the application - lowercase, no spaces. */
    static String getAppPackage() {
        return Metadata.current.getProperty('info.xh.appPackage', String).orElse(null)
    }

    /** User-facing display name of the application - proper case, can include spaces. */
    static String getAppName() {
        return Metadata.current.getProperty('info.xh.appName', String).orElse(null)
    }

    /** Current version, either SemVer x.y.z format or x.y-SNAPSHOT. */
    static String getAppVersion() {
        return Metadata.current.getProperty('info.app.version', String).orElse(null)
    }

    /** Git commit hash or other identifier set at build time. */
    static String getAppBuild() {
        return Metadata.current.getProperty('info.xh.appBuild', String).orElse(null)
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
    // App Start/Ready
    //------------------
    static final Date startupTime = new Date()

    /** True if the app instance is running and ready to start handling requests. */
    static boolean isInstanceReady() {
        try {
            return clusterService?.isReady
        } catch (Throwable ignored) {
            // We expect this to be routine for very premature calls to app - fail quietly.
            return false
        }
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

    static EnvironmentService getEnvironmentService() {
        return (EnvironmentService) appContext.environmentService
    }

    static IdentityService getIdentityService() {
        return (IdentityService) appContext.identityService
    }

    static LdapService getLdapService() {
        return (LdapService) appContext.ldapService
    }

    static PrefService getPrefService() {
        return (PrefService) appContext.prefService
    }

    static WebSocketService getWebSocketService() {
        return (WebSocketService) appContext.webSocketService
    }


    //------------------
    // Other Singletons
    //------------------
    /** Get the grails application context.  */
    static ApplicationContext getAppContext() {
        return Holders.applicationContext
    }

    /** Get the grails application config.  */
    static Config getGrailsConfig() {
        Holders.grailsApplication.config
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


    static testSerialization(Object obj, Class clazz, LogSupport logSupport, Map opts) {
        Kryo kryo = new Kryo()
        kryo.registrationRequired = false
        if (opts.java) {
            kryo.register(clazz, new JavaSerializer())
        }
        kryo.reset()
        kryo.setReferences(opts.refs)
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(32 * 1024)

        Long startTime = currentTimeMillis()
        OutputStream outputStream = byteStream
        if (opts.compress) outputStream = new DeflaterOutputStream(outputStream)
        Output output = new Output(outputStream)
        kryo.writeObject(output, obj);
        output.close()
        Long serializeTime = currentTimeMillis()

        InputStream inputStream = new ByteArrayInputStream(byteStream.toByteArray())
        if (opts.compress) inputStream = new InflaterInputStream(inputStream)
        Input input = new Input(inputStream)
        Object object2 = kryo.readObject(input, clazz)
        Long endTime = currentTimeMillis()

        logSupport.logInfo(
            opts,
            "(${serializeTime - startTime}/${endTime - serializeTime})ms",
            "${(byteStream.size() / 1000000).round(2)}MB",
            "${endTime - startTime}ms"
        )
    }

    private static terms = null
    private static List<String> getSensitiveParams() {
        if (terms == null) {
            terms = grailsConfig.getProperty('hoist.sensitiveParamTerms', ArrayList.class) as List<String>
        }
        return terms
    }

}
