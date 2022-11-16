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

              List<String> meta = args.collect { arg ->
                  switch (arg) {
                      case List: return delimitedTxt(arg.flatten())
                      case String:
                      case GString: return delimitedTxt([arg])
                      case Map: return delimitedTxt([arg])
                  }
              }

              if (msg) {
                  meta << msg
              }
              return meta.join(' | ')
          }

    //---------------------------------------------------------------------------
    // Implementation
    //---------------------------------------------------------------------------
    private String delimitedTxt(List msgs) {
        def username = identityService?.username
        List<String> ret = msgs.collect {
            it instanceof Throwable ? exceptionRenderer.summaryTextForThrowable(it) :
                it instanceof Map ? kvTxt(it) :
                    it.toString()
        }
        if (username) ret.add(username)
        return ret.join(' | ')
    }

    private String kvTxt(Map msgs) {
        List<String> ret = msgs.collect {k,v ->
            v = v instanceof Throwable ? exceptionRenderer.summaryTextForThrowable(v) : v.toString()

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
        return ret.findAll().join(' | ')
    }
}

