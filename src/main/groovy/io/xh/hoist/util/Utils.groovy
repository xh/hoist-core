/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import grails.util.Holders
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import io.xh.hoist.AppEnvironment
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.json.JSON
import io.xh.hoist.pref.PrefService
import io.xh.hoist.track.TrackLog
import io.xh.hoist.user.BaseRoleService
import io.xh.hoist.user.BaseUserService
import io.xh.hoist.websocket.WebSocketService
import org.grails.web.converters.exceptions.ConverterException
import org.grails.web.json.JSONArray
import org.grails.web.json.JSONObject
import org.springframework.context.ApplicationContext


@Slf4j
class Utils {

    static Properties buildInfo = readBuildInfo()
    static JsonSlurper validator = new JsonSlurper();

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

    static ConfigService getConfigService() {
        return (ConfigService) appContext.configService
    }

    static PrefService getPrefService() {
        return (PrefService) appContext.prefService
    }

    static BaseUserService getUserService() {
        return (BaseUserService) appContext.userService
    }

    static BaseRoleService getRoleService() {
        return (BaseRoleService) appContext.roleService
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

    static boolean isJSON(String val) {
        try {
            if (val != null) validator.parse(new StringReader(val))
            return true
        } catch (Exception ignored) {
            return false
        }
    }

    static Object stripJsonNulls(Object o) {
        if (o == null || o.equals(null)) return null
        if (o instanceof JSONArray) {
            o.eachWithIndex{v, idx -> o[idx] = stripJsonNulls(v)}
        }
        if (o instanceof JSONObject) {
            o.each{k, v -> o[k] = stripJsonNulls(v)}
        }
        return o
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
