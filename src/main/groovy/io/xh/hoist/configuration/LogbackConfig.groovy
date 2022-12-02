/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.configuration

import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.Context
import ch.qos.logback.core.Layout
import ch.qos.logback.core.encoder.Encoder
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import io.xh.hoist.util.Utils
import java.nio.file.Paths

import static ch.qos.logback.classic.Level.OFF
import static ch.qos.logback.classic.Level.ERROR
import static ch.qos.logback.classic.Level.INFO
import static ch.qos.logback.classic.Level.WARN
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import io.xh.hoist.log.DefaultConverter;

/**
 * This class supports the default logging configuration in Hoist.
 *
 * Applications should customize/specify their logging conventions via
 * the file grails-app/conf/logback.groovy.  See example-logback.txt
 * (in this directory) as well as the logback and grails documentation for
 * more information on how to construct this file.
 */
class LogbackConfig {

    private static _logRootPath = null

    /**
     * Layout used for for logging to stdout
     * String or a Closure that produces a Layout
     */
    static Object stdoutLayout = '%d{yyyy-MM-dd HH:mm:ss.SSS} | %c{0} [%p] | %m%n'

    /**
     * Layout for logs created by dailyLog() function
     * String or a Closure that produces a Layout
     * This layout will be used by the built-in rolling daily log provided by hoist.
     */
    static Object dailyLayout = '%d{HH:mm:ss.SSS} | %c{0} [%p] | %m%n'

    /**
     * Layout for logs created by monthlyLog() function
     * String or a Closure that produces a Layout
     */
    static Object monthlyLayout = '%d{MM-dd HH:mm:ss.SSS} | %c{0} [%p] | %m%n'

    /**
     * Layout used for logging monitor results to a dedicated log.
     * String or a Closure that produces a Layout
     */
    static Object monitorLayout = '%d{HH:mm:ss.SSS} | %m%n'

    /**
     * Layout used for logging client-side tracking results to a dedicated log.
     * String or a Closure that produces a Layout
     */
    static Object trackLayout = '%d{HH:mm:ss.SSS} | %m%n'


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
    static void defaultConfig(Script script) {
        withDelegate(script) {

            def appLogName = Utils.appCode,
                trackLogName = "$appLogName-track",
                monitorLogName = "$appLogName-monitor"

            //----------------------------------
            // Register Hoist-Core's Conversion Specifiers
            //----------------------------------
            conversionRule("m", DefaultConverter)
            conversionRule("msg", DefaultConverter)
            conversionRule("message", DefaultConverter)

            //----------------------------------
            // Appenders
            //----------------------------------
            appender('stdout', ConsoleAppender) {
                //noinspection UnnecessaryQualifiedReference
                encoder = LogbackConfig.createEncoder(stdoutLayout, context)
            }
            dailyLog(name: appLogName, script: script)
            dailyLog(name: trackLogName, script: script, layout: trackLayout)
            dailyLog(name: monitorLogName, script: script, layout: monitorLayout)

            //----------------------------------
            // Loggers
            //----------------------------------
            root(WARN, ['stdout', appLogName])

            // Raise Hoist and application to info
            logger('io.xh', INFO)
            logger(Utils.appPackage, INFO)

            // Loggers for MonitoringService and TrackService.
            // Do not duplicate in main log file, but write to stdout
            logger('io.xh.hoist.monitor.MonitoringService', INFO, [monitorLogName, 'stdout'], false)
            logger('io.xh.hoist.track.TrackService', INFO, [trackLogName, 'stdout'], false)

            // Quiet noisy loggers
            logger('org.hibernate', ERROR)
            logger('org.springframework', ERROR)
            logger('net.sf.ehcache', ERROR)

            // Turn off built-in global grails stacktrace logger.  It can easily swamp logs!
            // If needed, it can be (carefully) re-enabled by in admin console.
            // Applications should *not* typically enable -- instead Hoist stacktraces can be
            // enabled for any given logger by setting its level to TRACE
            logger('StackTrace', OFF)
        }
    }


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
                encoder = LogbackConfig.createEncoder(config.layout ?: dailyLayout, context)
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
                encoder = LogbackConfig.createEncoder(config.layout ?: monthlyLayout, context)
                rollingPolicy(TimeBasedRollingPolicy)   {fileNamePattern = fileName + ".%d{yyyy-MM}.log"}
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
