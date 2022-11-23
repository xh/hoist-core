package io.xh.hoist.log

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy

import static io.xh.hoist.util.Utils.exceptionRenderer

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

