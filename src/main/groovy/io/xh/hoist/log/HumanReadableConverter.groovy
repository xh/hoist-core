package io.xh.hoist.log

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy

import static io.xh.hoist.util.Utils.exceptionRenderer
import static io.xh.hoist.util.Utils.identityService

class HumanReadableConverter extends ClassicConverter {

          @Override
          public String convert(ILoggingEvent event) {
              def msg = event.message

              if (!msg.startsWith('USE_XH_LOG_SUPPORT')) {
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

              List<String> processed = args.collect { delimitedTxt(flatten(it)) }.flatten()

              def username = null
              try{username = identityService.username} catch(ignored) {}
              if (username) processed << username

              return processed.findAll().join(' | ') + tStack
          }

    //---------------------------------------------------------------------------
    // Implementation
    //---------------------------------------------------------------------------
    private List<String> delimitedTxt(List msgs) {
        return msgs.collect {
            it instanceof Throwable ? safeErrorSummary(it) :
                it instanceof Map ? kvTxt(it) :
                    it.toString()
        }.flatten()
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

    private List flatten(Object[] msgs) {
        Arrays.asList(msgs).flatten()
    }
}

