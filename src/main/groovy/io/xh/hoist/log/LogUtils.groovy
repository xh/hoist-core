/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.Context
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.Layout
import ch.qos.logback.core.encoder.Encoder
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import grails.util.BuildSettings
import grails.util.Environment
import io.xh.hoist.util.Utils
import java.nio.file.Paths

import static ch.qos.logback.classic.Level.ERROR
import static ch.qos.logback.classic.Level.INFO
import static ch.qos.logback.classic.Level.WARN
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig

/**
 * This class supports the default logging configuration in Hoist.
 *
 * Applications should customize/specify their logging conventions via
 * the file grails-app/conf/logback.groovy.  See sample-logback.groovy
 * (in this directory) as well as the logback and grails documentation for
 * more information on how to construct this file.
 */
class LogUtils {

    private static _logRootPath = null

    /**
     * Layout used for for logging to stdout
     * String or a Closure that produces a Layout
     */
    static Object stdoutLayout = '%d{yyyy-MM-dd HH:mm:ss} | %c{0} [%p] | %m%n'

    /**
     * Layout for logs created by dailyLog() function
     * String or a Closure that produces a Layout
     * This layout will be used by the built-in rolling daily log provided by hoist.
     */
    static Object dailyLayout = '%d{HH:mm:ss} | %c{0} [%p] | %m%n'

    /**
     * Layout for logs created by monthlyLog() function
     * String or a Closure that produces a Layout
     */
    static Object monthlyLayout = '%d{MM-dd HH:mm:ss} | %c{0} [%p] | %m%n'

    /**
     * Layout used for logging monitor results to its dedicated monitor log.
     * String or a Closure that produces a Layout
     */
    static Object monitorLayout = '%d{HH:mm:ss} | %m%n'


    /**
     * Return the logging directory path - [tomcatHome]/logs/[appName]-logs by default.
     * Apps can specify a custom directory via a `-Dio.xh.hoist.log.path` JavaOpt or a
     * `logPath` instance config entry.
     */
    static String getLogRootPath() {
        if (!_logRootPath) {
            def customPath = System.getProperty('io.xh.hoist.log.path') ?: getInstanceConfig('logPath')

            if (customPath) {
                _logRootPath = customPath
            } else {
                def tomcatHomeDir = System.getProperty('catalina.base', ''),
                    logSubDir = tomcatHomeDir ? 'logs' : ''

                _logRootPath = Paths.get(tomcatHomeDir, logSubDir, "${Utils.appCode}-logs").toString()
            }
        }
        return _logRootPath
    }

    /**
     * Create an appender for a daily rolling log.
     *
     * @param config.name - name of log/appender
     * @param config.script - logback script where this method is being called from
     * @param config.subdir - subdirectory within main logging directory to place log (optional)
     * @param config.layout - string or Logback layout to be used.  Defaults to dailyLayout (optional)
     */
    static void dailyLog(Map config) {
        def name = config.name,
            subdir = config.subdir ?: '',
            fileName = Paths.get(logRootPath, subdir, name).toString()

        withDelegate(config.script) {
            appender(name, RollingFileAppender) {
                file = fileName + '.log'
                encoder = LogUtils.createEncoder(config.layout ?: dailyLayout, context)
                rollingPolicy(TimeBasedRollingPolicy) { fileNamePattern = fileName + ".%d{yyyy-MM-dd}.log" }
            }
        }
    }

    /**
     * Create an appender for a monthly rolling log.
     *
     * @param config.name - name of log/appender
     * @param config.script - logback script where this method is being called from
     * @param config.subdir - subdirectory within main logging directory to place log (optional)
     * @param config.layout - string or Logback layout to be used.  Defaults to monthlyLayout (optional)
     */
    static void monthlyLog(Map config) {
        def name = config.name,
            subdir = config.subdir ?: '',
            fileName = Paths.get(logRootPath, subdir, name).toString()

        withDelegate(config.script) {
            appender(name, RollingFileAppender) {
                file = fileName + '.log'
                encoder = LogUtils.createEncoder(config.layout ?: monthlyLayout, context)
                rollingPolicy(TimeBasedRollingPolicy)   {fileNamePattern = fileName + ".%d{yyyy-MM}.log"}
            }
        }
    }

    /**
     * Main entry point.
     *
     * This function sets up "built-in" appenders for stdout, a daily rolling log,
     * and logs for Hoists built-in monitoring.
     *
     * It will also setup default logging levels logging levels for application, Hoist, and other
     * third-party packages. Note that these logging levels can be overwritten statically by
     * applications in logback.groovy.
     *
     * Application logback scripts need to call this method in their logback.groovy file.
     * See example-logback.groovy in this directory for more details.
     *
     * @param script
     */
    static void initConfig(Script script) {
        withDelegate(script) {
            def appLogName = Utils.appCode,
                monitorLogName = "$appLogName-monitor"

            //----------------------------------
            // Appenders
            //----------------------------------
            appender('stdout', ConsoleAppender) {
                encoder = LogUtils.createEncoder(stdoutLayout, context)
            }
            dailyLog(name: appLogName, script: script)
            dailyLog(name: monitorLogName, script: script, layout: monitorLayout)

            //----------------------------------
            // Loggers
            //----------------------------------
            root(WARN, ['stdout', appLogName])

            // Raise Hoist to info
            logger('io.xh', INFO)

            // Logger for MonitoringService only. Do not duplicate in main log file, but write to stdout
            logger('io.xh.hoist.monitor.MonitoringService', INFO, [monitorLogName, 'stdout'], additivity = false)

            // Quiet noisy loggers
            logger('org.hibernate',                 ERROR)
            logger('org.springframework',           ERROR)
            logger('net.sf.ehcache',                ERROR)

            //------------------------------------------------------------
            // Full Stack trace, redirect to special log in dev mode only
            //------------------------------------------------------------
            def targetDir = BuildSettings.TARGET_DIR
            if (Environment.isDevelopmentMode() && targetDir) {
                appender('stacktrace', FileAppender) {
                    file = "$targetDir/stacktrace.log"
                    append = true
                    encoder(PatternLayoutEncoder) {pattern = "%level %logger - %msg%n" }
                }
                logger('StackTrace', ERROR, ['stacktrace'], false)
            }
        }
    }


    //------------------------
    // Implementation
    //------------------------
    private static void withDelegate(Object o, Closure c) {
        c.delegate = o
        c.call()
    }

    private static Encoder createEncoder(Object layoutSpec, Context context) {
        Encoder ret
        if (layoutSpec instanceof String) {
            ret = new PatternLayoutEncoder()
            ret.pattern = layoutSpec
        } else {
            ret = new LayoutWrappingEncoder()
            Layout layout = layoutSpec.call();
            layout.context = context
            layout.start();
            ret.layout = layout
        }

        ret.context = context
        ret.start()
        return ret
    }
}
