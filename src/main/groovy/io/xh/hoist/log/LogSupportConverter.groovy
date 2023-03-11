package io.xh.hoist.log

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy

import static io.xh.hoist.util.Utils.exceptionRenderer

/**
 * Layout Converter to output log messages in a human readable layout.
 *
 * This converter is Hoist Core's default converter for messages going to stdout
 * and rolled log files.
 *
 * It is referenced in layout strings with the key `%m`, `%msg`, or `%message`,
 * overriding the same Logback keys.
 *
 * This class implements the following conventions
 *
 * - Arguments and lists passed to log support will be pipe (`|`) separated.
 * - For Map arguments, keys that start with an underscore _ will not be printed.
 *     ie: [_status: 'completed'] becomes 'completed'
 * - The '_elapsedMs' key is special: it is converted to the suffix 'ms'
 *     ie: [_elapsedMs: 3000] becomes '3000ms'
 *
 * Developers wishing to output log entries with a different layout can create their own converter
 * and layout strings in their application's /grails-app/conf/logback.groovy file.
 */
class LogSupportConverter extends ClassicConverter {

    String convert(ILoggingEvent event) {
        if (!(event.marker instanceof LogSupportMarker)) return event.formattedMessage

        LogSupportMarker marker = event.marker as LogSupportMarker;
        List messages = marker.messages.flatten()

        // 1) Core of the messages is just pipe delimited.
        def ret = messages
            .collect { formatObject(it) }
            .join(delimiter)

        // 2) Potentially append stack trace on trace.
        if (marker.logger.isTraceEnabled() && messages.last() instanceof Throwable) {
            ret += formatStacktrace(messages.last() as Throwable)
        }

        return ret
    }

    //---------------------------------
    // Protected methods for Override
    //---------------------------------
    protected String getDelimiter() {
        return ' | '
    }

    protected String formatStacktrace(Throwable t) {
        String indent = '           '
        return '\n' + indent + new ThrowableProxy(t)
            .stackTraceElementProxyArray
            .collect { it.getSTEAsString() }
            .join('\n' + indent)
    }

    protected String formatObject(Object obj) {
        if (obj instanceof Throwable) return formatThrowable(obj)
        if (obj instanceof Map) return formatMap(obj)
        return obj.toString()
    }

    protected String formatMap(Map msgs) {
        return msgs.collect { k, v ->
            v = v instanceof Throwable ? formatThrowable(v) : v.toString()

            if (k == '_elapsedMs') {
                return "${v}ms"
            }

            if (k instanceof String && k.startsWith('_')) {
                return v
            }

            return "$k=$v"
        }.join (delimiter)
    }

    protected String formatThrowable(Throwable t) {
        try {
            return exceptionRenderer.summaryTextForThrowable(t)
        } catch (Exception ignored) {
            return t.message
        }
    }
}

