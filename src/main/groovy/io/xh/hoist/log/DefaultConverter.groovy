package io.xh.hoist.log

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy

import static io.xh.hoist.util.Utils.exceptionRenderer

/**
 * Converter to output log messages in a human readable layout.
 * This converter is Hoist-Core's default converter for messages going to stdout
 * and to Hoist-Core's rolled log files.
 *
 * It is referenced in layout strings with the key `%defaultMsg`.
 *
 * "Human Readable" is, admittedly, a subjective qualification.
 * In this case, it means that:
 * - Arguments passed to log support will be pipe | separated.
 * - Maps with string values will not print their keys, the assumption being that the human will
 *   be able to infer the significance of the bare string values from context,
 *   ie: (status: 'completed' becomes 'completed')
 * - Maps with numeric values will preserve their keys, ie: (UsedMB: 300 becomes 'UsedMB=300'),
 *   since the context of numeric values is typically harder to infer.
 *   An exception for mapped numeric values is made for elapsedMs=3000, which is converted to
 *   simply 3000ms and put at the end of the log msg.
 * - Lists of Strings or Numbers will be rendered pipe | separated.
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

              return processed.findAll().join(' | ') + tStack
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
        return msgs.collect {k,v ->
            v = v instanceof Throwable ? safeErrorSummary(v) : v.toString()

            if (v.isNumber()) {
                if (k.startsWith('elapsed')) {
                    k = k.replace('elapsed', '').toLowerCase()
                    v = "$v$k"
                } else {
                    v = "$k=$v"
                }
            }

           return v
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

