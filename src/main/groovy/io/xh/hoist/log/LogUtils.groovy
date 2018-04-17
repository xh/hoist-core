/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import grails.util.BuildSettings
import grails.util.Environment
import io.xh.hoist.util.Utils
import java.nio.file.Paths

import static ch.qos.logback.classic.Level.ERROR
import static ch.qos.logback.classic.Level.INFO
import static ch.qos.logback.classic.Level.WARN

class LogUtils {

    static String DEFAULT_FULL_LAYOUT =      '%d{yyyy-MM-dd HH:mm:ss} | %c{0} [%p] | %m%n'
    static String DEFAULT_MONTHLY_LAYOUT =   '%d{MM-dd HH:mm:ss} | %c{0} [%p] | %m%n'
    static String DEFAULT_DAILY_LAYOUT =     '%d{HH:mm:ss} | %c{0} [%p] | %m%n'

    private static _logRootPath = null

    /**
     * Return the logging directory path - [tomcatHome]/logs/[app-name]-logs by default.
     * Apps can specify a custom directory via a `-Dio.xh.hoist.log.path` Java opt.
     */
    static String getLogRootPath() {
        if (!_logRootPath) {
            def customPath = System.getProperty('io.xh.hoist.log.path')
            if (customPath) {
                _logRootPath = customPath
            } else {
                def tomcatHomeDir = System.getProperty('catalina.base', ''),
                    logSubDir = tomcatHomeDir ? 'logs' : ''

                _logRootPath = Paths.get(tomcatHomeDir, logSubDir, "${Utils.appName}-logs").toString()
            }
        }
        return _logRootPath
    }

    static dailyLog(Map config) {
        def name = config.name ?: '',
            subdir = config.subdir ?: '',
            fileName = Paths.get(logRootPath, subdir, name).toString(),
            logPattern = config.pattern ?: DEFAULT_DAILY_LAYOUT

        withDelegate(config.script) {
            appender(name, RollingFileAppender) {
                file = fileName + '.log'
                encoder(PatternLayoutEncoder)           {pattern = logPattern}
                rollingPolicy(TimeBasedRollingPolicy)   {fileNamePattern = fileName + ".%d{yyyy-MM-dd}.log"}
            }
        }
    }

    static monthlyLog(Map config) {
        def name = config.name ?: '',
            subdir = config.subdir ?: '',
            fileName = Paths.get(logRootPath, subdir, name).toString(),
            logPattern = config.pattern ?: DEFAULT_MONTHLY_LAYOUT

        withDelegate(config.script) {
            appender(name, RollingFileAppender) {
                file = fileName + '.log'
                encoder(PatternLayoutEncoder)           {pattern = logPattern}
                rollingPolicy(TimeBasedRollingPolicy)   {fileNamePattern = fileName + ".%d{yyyy-MM}.log"}
            }
        }
    }

    static void initConfig(Script script) {
        withDelegate(script) {

            def appLogName = Utils.appName

            //----------------------------------
            // Appenders
            //----------------------------------
            appender('stdout', ConsoleAppender) {
                encoder(PatternLayoutEncoder) {pattern = DEFAULT_FULL_LAYOUT}
            }

            dailyLog(name: appLogName, script: script)

            //----------------------------------
            // Loggers
            //----------------------------------
            root(WARN, ['stdout', appLogName])

            // Raise Hoist to info
            logger('io.xh', INFO)

            // Quiet noisy loggers
            logger('org.hibernate',                 ERROR)
            logger('org.springframework',           ERROR)
            logger('net.sf.ehcache',                ERROR)

            //----------------------------------
            // Full Stack trace, redirect to special log in dev mode only
            //----------------------------------
            def targetDir = BuildSettings.TARGET_DIR
            if (Environment.isDevelopmentMode() && targetDir) {
                appender('stacktrace', FileAppender) {
                    file = "${targetDir}/stacktrace.log"
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

}
