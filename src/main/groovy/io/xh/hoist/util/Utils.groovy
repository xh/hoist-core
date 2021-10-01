/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import grails.util.Environment
import grails.util.Holders
import io.xh.hoist.AppEnvironment
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.environment.EnvironmentService
import io.xh.hoist.exception.ExceptionRenderer
import io.xh.hoist.pref.PrefService
import io.xh.hoist.track.TrackLog
import io.xh.hoist.user.BaseRoleService
import io.xh.hoist.user.BaseUserService
import io.xh.hoist.user.IdentityService
import io.xh.hoist.websocket.WebSocketService
import org.springframework.context.ApplicationContext


class Utils {

    static Properties buildInfo = readBuildInfo()

    static final Date startupTime = new Date()

    /**
     * Internal short name of the application - lowercase, no spaces.
     */
    static String getAppCode() {
        return buildInfo.getProperty('info.xh.appCode')
    }

    /**
     * User-facing display name of the application - proper case, can include spaces.
     */
    static String getAppName() {
        return buildInfo.getProperty('info.xh.appName')
    }

    /**
     * Current version, either SemVer x.y.z format or x.y-SNAPSHOT.
     */
    static String getAppVersion() {
        return buildInfo.getProperty('info.app.version')
    }

    /**
     * Optional git commit hash or other identifier set at build time.
     */
    static String getAppBuild() {
        return buildInfo.getProperty('info.xh.appBuild')
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
    static Map<String, String> getDataSource() {
        return (Map<String, String>) Holders.grailsApplication.config.dataSource
    }

    /**
     * Run a closure with a new hibernate session.  Useful for asynchronous routines that will not
     * have a Grails-installed Hibernate session on the thread.
     */
    static withNewSession(Closure c) {
        TrackLog.withNewSession(c) // Yes, a bizarre dependency on an arbitrary domain object
    }

    // TODO:  Move to Jackson when we are on Grails 4/Jackson 2.9:
    // Jackson 2.9 has the support for FAIL_ON_TRAILING_TOKENS that we need
    static boolean isJSON(String val) {
        try {
            if (val != null) JsonParser.any().from(val)
            return true
        } catch (JsonParserException ignored) {
            return false
        }
    }

    /**
     * Return all singleton instances of io.xh.BaseService in the application
     */
    static List<BaseService> getXhServices() {
        return appContext.getBeansOfType(BaseService, false, true).collect {it.value}
    }


    //------------------------
    // Implementation
    //------------------------
    // We *should* be able to draw this build info from grails.util.Metadata object.
    // But that object began returning nulls with grails 3.3.0.
    // For now, we just pulls values directly from the gradle artifact used by that file.
    // Note that our standard build.gradle injects appCode/appName
    // See http://grailsblog.objectcomputing.com/posts/2017/04/02/add-build-info-to-your-project.html
    private static Properties readBuildInfo() {
        def ret = new Properties(),
            loader = Thread.currentThread().getContextClassLoader(),
            file = 'META-INF/grails.build.info',
            url = loader.getResource(file) ?: loader.getResource('../../' + file)

        if (url) {
            url.withInputStream {ret.load(it)}
        }

        return ret
    }

}
