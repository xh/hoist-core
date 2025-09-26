/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.Level
import ch.qos.logback.core.Layout
import ch.qos.logback.core.encoder.Encoder
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.log.ClusterInstanceConverter
import io.xh.hoist.log.LogSupportConverter
import io.xh.hoist.util.Utils
import java.nio.file.Paths

import static ch.qos.logback.classic.Level.OFF
import static ch.qos.logback.classic.Level.ERROR
import static ch.qos.logback.classic.Level.INFO
import static ch.qos.logback.classic.Level.WARN
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static org.slf4j.Logger.ROOT_LOGGER_NAME

/**
 * This class supports the default logging configuration in Hoist.
 *
 * Apps wishing to customize logging should create a subclass of this class and
 * override its configureLogging() endpoint.
 */
class LogbackConfig {

    private static String _logRootPath = null


    /** Internal, set by framework */
    public LoggerContext context = null
    private Map<String, Appender> appenders = [:];

    /**
     * Layout used for for logging to stdout
     * String or a Closure that produces a Layout
     */
    static Object stdoutLayout = '%d{yyyy-MM-dd HH:mm:ss.SSS} | %instance | %c{0} [%p] | %m%n'

    /**
     * Layout for logs created by dailyLog() function
     * String or a Closure that produces a Layout
     * This layout will be used by the built-in rolling daily log provided by hoist.
     */
    static Object dailyLayout = '%d{HH:mm:ss.SSS} | %instance | %c{0} [%p] | %m%n'

    /**
     * Layout for logs created by monthlyLog() function
     * String or a Closure that produces a Layout
     */
    static Object monthlyLayout = '%d{MM-dd HH:mm:ss.SSS} | %instance | %c{0} [%p] | %m%n'

    /**
     * Layout used for logging monitor results to a dedicated log.
     * String or a Closure that produces a Layout
     */
    static Object monitorLayout = '%d{HH:mm:ss.SSS} | %instance | %m%n'

    /**
     * Layout used for logging client-side tracking results to a dedicated log.
     * String or a Closure that produces a Layout.
     *
     * Note that for the purposes of this log, we skip writing metadata, especially log timestamp,
     * These are application events that are reported with debouncing - they contain their own
     * timestamp and are given special handling by {@link io.xh.hoist.track.TrackLoggingService}
     * to preserve the order of events.
     */
    static Object trackLayout = '%m%n'


    /**
     * Main entry point.
     *
     * This function sets up "built-in" appenders for stdout, a daily rolling log, and additional
     * dedicated logs for Hoist's built-in activity tracking and status monitoring.
     *
     * It will also setup default logging levels logging levels for application, Hoist, and select
     * third-party packages. Note that these logging levels can be overwritten statically by
     * applications in logback.groovy.
     *
     * Applications should override this m
     */
    void configLogging() {
        def appLogRoot = "${Utils.appCode}-${ClusterService.instanceName}",
            appLogName = "$appLogRoot-app",
            trackLogName = "$appLogRoot-track",
            monitorLogName = "$appLogRoot-monitor"

        //----------------------------------
        // Register Hoist-Core's Conversion Specifiers
        //----------------------------------
        conversionRule("m", LogSupportConverter)
        conversionRule("msg", LogSupportConverter)
        conversionRule("message", LogSupportConverter)
        conversionRule("instance", ClusterInstanceConverter)

        //----------------------------------
        // Appenders
        //----------------------------------
        consoleAppender('stdout')
        dailyLog(appLogName)
        dailyLog(trackLogName, trackLayout)
        dailyLog(monitorLogName, monitorLayout)

        //----------------------------------
        // Loggers
        //----------------------------------
        root(WARN, ['stdout', appLogName])

        // Raise Hoist and application to info
        logger('io.xh', INFO)
        logger(Utils.appPackage, INFO)

        // Dedicated non-appending Loggers for monitoring and tracking
        logger('io.xh.hoist.monitor.MonitorEvalService', INFO, [monitorLogName, 'stdout'], false)
        logger('io.xh.hoist.track.TrackLoggingService', INFO, ['stdout'], false)
        logger('io.xh.hoist.track.TrackLoggingService.Log', INFO, [trackLogName], false)


        // Quiet noisy loggers
        logger('org.springframework', ERROR)
        logger('org.hibernate', ERROR)
        logger('org.apache.directory.ldap.client.api.LdapNetworkConnection', ERROR)

        // Stifle warning about disabled strong consistency library -- requires 3 node min.
        logger('com.hazelcast.cp.CPSubsystem', ERROR)

        // Turn off built-in global grails stacktrace logger.  It can easily swamp logs!
        // If needed, it can be (carefully) re-enabled by in admin console.
        // Applications should *not* typically enable -- instead Hoist stacktraces can be
        // enabled for any given logger by setting its level to TRACE
        logger('StackTrace', OFF)
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

    //--------------------
    // Helpers
    //---------------------
    /**
     * Create an appender for a daily rolling log.
     *
     * @param name - name of log/appender
     * @param subdir - subdirectory within main logging directory to place log (optional)
     * @param layout - string or Logback layout to be used.  Defaults to dailyLayout (optional)
     */
    Appender dailyLog(String name, Object layout = dailyLayout, String subdir = '') {
        def fileName = Paths.get(logRootPath, subdir, name).toString()

        def ret = new RollingFileAppender()
        ret.context = context
        ret.name = name
        ret.file = fileName + ".log"
        ret.encoder = createEncoder(layout)
        ret.rollingPolicy = new TimeBasedRollingPolicy().tap {
            context = context
            fileNamePattern = fileName + ".%d{yyyy-MM-dd}.log"
            parent = ret
            start()
        }
        ret.start()
        return appenders[name] = ret
    }

    /**
     * Create an appender for a monthly rolling log.
     *
     * @param config.name - name of log/appender
     * @param subdir - subdirectory within main logging directory to place log (optional)
     * @param layout - string or Logback layout to be used.  Defaults to monthlyLayout (optional)
     */
    protected Appender monthlyLog(String name, Object layout = monthlyLayout, String subdir = '') {
        def fileName = Paths.get(logRootPath, subdir, name).toString()

        def ret = new RollingFileAppender()
        ret.context = context
        ret.name = name
        ret.file = fileName + ".log"
        ret.encoder = createEncoder(layout)
        ret.rollingPolicy = new TimeBasedRollingPolicy().tap {
            context = context
            fileNamePattern = fileName + ".%d{yyyy-MM}.log"
            parent = ret
            start()
        }
        ret.start()
        return appenders[name] = ret
    }

    /**
     * Create an appender for the console
     *
     * @param name - name of log/appender
     * @param layout - string or Logback layout to be used.
     */
    protected Appender consoleAppender(String name, Object layout = stdoutLayout) {
        def ret = new ConsoleAppender()
        ret.name = name
        ret.encoder = createEncoder(stdoutLayout)
        ret.start()
        return appenders[name] = ret
    }

    /**
     * Create an Encoder for a layout
     *
     * @param layoutSpec - string or Logback layout to be used.
     */
    protected Encoder createEncoder(Object layoutSpec) {
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

    protected Logger root(Level level, List<String> appenderNames = []) {
        logger(ROOT_LOGGER_NAME, level, appenderNames);
    }

    /**
     * Set the level for a package or class, and assign to appenders.
     */
    protected Logger logger(String name, Level level, List<String> appenderNames = [], Boolean additivity = null) {
        Logger ret = context.getLogger(name)
        ret.level = level
        for (aName in appenderNames) {
            Appender appender = appenders[aName]
            if (appender) {
                ret.addAppender(appender)
            }
        }
        if (additivity != null) {
            ret.additive = additivity
        }
        return ret
    }

    /**
     * Register a conversion word.
     */
    void conversionRule(String conversionWord, Class converterClass) {
        String converterClassName = converterClass.name

        Map<String, String> ruleRegistry = (Map) context.getObject(CoreConstants.PATTERN_RULE_REGISTRY);
        if (ruleRegistry == null) {
            ruleRegistry = new HashMap<String, String>()
            context.putObject(CoreConstants.PATTERN_RULE_REGISTRY, ruleRegistry)
        }
        ruleRegistry[conversionWord] = converterClassName
    }
}