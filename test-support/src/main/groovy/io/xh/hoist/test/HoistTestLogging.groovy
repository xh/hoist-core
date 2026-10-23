/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import groovy.transform.CompileStatic
import io.xh.hoist.log.LogSupportConverter
import org.slf4j.LoggerFactory

import static ch.qos.logback.core.CoreConstants.PATTERN_RULE_REGISTRY

/**
 * Logback setup for the test JVM, applied by {@link HoistSpockGlobalExtension} before any spec runs.
 *
 * An app's `LogbackConfig` does not run in a unit test, so Logback would otherwise fall back to
 * logging everything at DEBUG, and would render every Hoist `logInfo` / `logWarn` message as
 * `null` - Hoist carries the message on a Marker that only {@link LogSupportConverter} knows how
 * to read. This class instead installs a console appender at WARN with that converter registered
 * for `%m`, `%msg` and `%message`.
 *
 * To see more from a particular logger while working on a spec, set its level in the spec:
 * `(LoggerFactory.getLogger(MyService) as ch.qos.logback.classic.Logger).level = Level.DEBUG`.
 */
@CompileStatic
final class HoistTestLogging {

    static final String PATTERN = '%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n'

    private HoistTestLogging() {}

    /** Replace whatever Logback configured on startup with the test setup described above. */
    static synchronized void configure() {
        def factory = LoggerFactory.getILoggerFactory()
        if (!(factory instanceof LoggerContext)) return

        LoggerContext context = (LoggerContext) factory
        context.reset()

        Map<String, String> rules = (Map<String, String>) context.getObject(PATTERN_RULE_REGISTRY)
        if (rules == null) {
            rules = [:]
            context.putObject(PATTERN_RULE_REGISTRY, rules)
        }
        ['m', 'msg', 'message'].each { rules[it] = LogSupportConverter.name }

        def encoder = new PatternLayoutEncoder(context: context, pattern: PATTERN)
        encoder.start()

        def appender = new ConsoleAppender<ILoggingEvent>(context: context, name: 'CONSOLE', encoder: encoder)
        appender.start()

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        root.level = Level.WARN
        root.addAppender(appender)
    }
}
