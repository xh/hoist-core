package io.xh.hoist.log

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

import static io.xh.hoist.util.Utils.exceptionRenderer
import static io.xh.hoist.util.Utils.identityService

class HumanReadableConverter extends ClassicConverter {

          @Override
          public String convert(ILoggingEvent event) {
              def msg = event.message
              def args = event.argumentArray
              def username = null
              try{username = identityService.username} catch(ignored) {}

              List<String> ret = args.collect { arg ->
                  switch (arg) {
                      case List: return delimitedTxt(arg.flatten())
                      default: return delimitedTxt([arg])
                  }
              }.flatten()

              if (msg) {
                  ret << msg
              }
              if (username) ret.add(username)
              return ret.findAll().join(' | ')
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
}

