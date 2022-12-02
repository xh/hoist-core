package io.xh.hoist.log

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy

import static io.xh.hoist.util.Utils.exceptionRenderer

/**
 * Logback Layout Converter to output log messages in a human readable layout.
 * This converter is Hoist-Core's default converter for messages going to stdout
 * and to Hoist-Core's rolled log files.
 *
 * It is referenced in layout strings with the key `%m`, `%msg`, or `%message`,
 * overriding the same Logback keys.
 *
 * "Human Readable" is, admittedly, a subjective qualification.
 * In this case, it means that:
 * - Arguments passed to log support will be pipe | separated.
 * - For Map arguments, keys that start with an underscore _ will not be printed.
 *     ie: [_status: 'completed'] becomes 'completed'
 *   The '_elapsedMs' key is special: it is converted to the suffix 'ms'
 *     ie: [_elapsedMs: 3000] becomes '3000ms'
 * - Lists will be rendered pipe | separated.
 *
 * Developers wishing to output log entries with a different layout can create their own converter and
 * override the layout strings in Hoist-Core's @class LogbackConfig with their own layout strings in
 * their application's /grails-app/conf/logback.groovy file.
 *
 */
class DefaultConverter extends ClassicConverter {

          @Override
          public String convert(ILoggingEvent event) {
              def msg = event.message

              if (!msg?.startsWith('USE_XH_LOG_SUPPORT')) {
                  return event.formattedMessage
              }

              def args = event.argumentArray.flatten()

              String tStack = ''
              if (msg == 'USE_XH_LOG_SUPPORT_WITH_STACKTRACE') {
                  Throwable t = getThrowable(args)
                  if (t) {
                      args.removeLast()
                      String indent = '           '
                      tStack = '\n' + indent + new ThrowableProxy(t)
                          .stackTraceElementProxyArray
                          .collect {it.getSTEAsString()}
                          .join('\n' + indent)
                  }
              }

              List<String> processed = args.collect { delimitedTxt(it) }.flatten()

              return processed.join(' | ') + tStack
          }

    //---------------------------------------------------------------------------
    // Implementation
    //---------------------------------------------------------------------------
    private List<String> delimitedTxt(Object obj) {
        if (!obj) return []
        if (obj instanceof Throwable) return [safeErrorSummary(obj)]
        if (obj instanceof Map) return kvTxt(obj)
        return [obj.toString()]
    }

    private List<String> kvTxt(Map msgs) {
        return msgs.collect {k, v ->
            v = v instanceof Throwable ? safeErrorSummary(v) : v.toString()

            if (k.startsWith('_elapsedMs')) {
                return "${v}ms"
            }

            if (k.startsWith('_')) {
                return v
            }

            return "$k=$v"
        }
    }

    private safeErrorSummary(Throwable t) {
        def ret = t.message
        try{ret = exceptionRenderer.summaryTextForThrowable(t)} catch(ignored) {}
        return ret
    }

    private Throwable getThrowable(List msgs) {
        def last = msgs.last()
        return last instanceof Throwable ? last : null
    }
}

