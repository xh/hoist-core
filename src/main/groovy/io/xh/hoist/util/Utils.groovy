/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import io.xh.hoist.json.JSONParser
import grails.util.Environment
import grails.util.Holders
import grails.util.Metadata
import io.xh.hoist.AppEnvironment
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.environment.EnvironmentService
import io.xh.hoist.exception.ExceptionRenderer
import io.xh.hoist.pref.PrefService
import io.xh.hoist.user.BaseRoleService
import io.xh.hoist.user.BaseUserService
import io.xh.hoist.user.IdentityService
import io.xh.hoist.websocket.WebSocketService
import org.springframework.context.ApplicationContext


class Utils {

    static final Date startupTime = new Date()

    /**
     * Internal short name of the application - lowercase, no spaces.
     */
    static String getAppCode() {
        return Metadata.current.getProperty('info.xh.appCode', String).orElse(null)
    }

    /**
     * User-facing display name of the application - proper case, can include spaces.
     */
    static String getAppName() {
        return Metadata.current.getProperty('info.xh.appName', String).orElse(null)
    }

    /**
     * Current version, either SemVer x.y.z format or x.y-SNAPSHOT.
     */
    static String getAppVersion() {
        return Metadata.current.getProperty('info.app.version', String).orElse(null)
    }

    /**
     * git commit hash or other identifier set at build time.
     */
    static String getAppBuild() {
        return Metadata.current.getProperty('info.xh.appBuild', String).orElse(null)
    }

    /**
     * Hoist AppEnvironment of the current deployment, distinct from Grails environment.
     */
    static AppEnvironment getAppEnvironment() {
        return InstanceConfigUtils.appEnvironment
    }

    static Boolean getIsProduction() {
        return appEnvironment == AppEnvironment.PRODUCTION
    }

    /**
     * Indicates if app is running in local development mode, regardless of AppEnvironment.
     */
    static Boolean getIsLocalDevelopment() {
        return Environment.isDevelopmentMode()
    }

    static ConfigService getConfigService() {
        return (ConfigService) appContext.configService
    }

    static PrefService getPrefService() {
        return (PrefService) appContext.prefService
    }

    static EnvironmentService getEnvironmentService() {
        return (EnvironmentService) appContext.environmentService
    }

    static BaseUserService getUserService() {
        return (BaseUserService) appContext.userService
    }

    static IdentityService getIdentityService() {
        return (IdentityService) appContext.identityService
    }

    static BaseRoleService getRoleService() {
        return (BaseRoleService) appContext.roleService
    }

    static ExceptionRenderer getExceptionRenderer() {
        return (ExceptionRenderer) appContext.exceptionRenderer
    }

    static WebSocketService getWebSocketService() {
        return (WebSocketService) appContext.webSocketService
    }

    static ApplicationContext getAppContext() {
        return Holders.applicationContext
    }

    /**
     * Return the app's primary dataSource configuration. This is the connection to the app's
     * database housing Hoist-related tables as well as any app-specific domain objects.
     */
    static Map getDataSource() {
        Holders.grailsApplication
            .config
            .getProperty('dataSource', Map.class)
            .collectEntries {it}
    }

    /**
     * Return true if a String represents valid JSON.
     */
    static boolean isJSON(String val) {
        JSONParser.validate(val)
    }

    /**
     * Return all singleton instances of io.xh.BaseService in the application
     */
    static List<BaseService> getXhServices() {
        return appContext.getBeansOfType(BaseService, false, true).collect {it.value}
    }

    /**
     * Execute a closure with a given delegate.
     *
     * Useful for applying configuration to a script. See ApplicationConfig.applyDefault()
     */
    static void withDelegate(Object o, Closure c) {
        c.delegate = o
        c.call()
    }
}
