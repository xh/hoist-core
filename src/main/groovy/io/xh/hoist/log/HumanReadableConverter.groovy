package io.xh.hoist.log

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

import static io.xh.hoist.util.Utils.getExceptionRenderer
import static io.xh.hoist.util.Utils.getIdentityService

class HumanReadableConverter extends ClassicConverter {

          @Override
          public String convert(ILoggingEvent event) {
              def ret = event.message
              ret = ret.replaceAll(/="/, '=')
                  .replaceAll(/" \| /, ' | ')
                  .replaceAll(/"$/, '')
              ret = ret.replaceAll(/\| elapsed(\w\w?)=(\d+)/) {"| ${it[2]}${it[1].toLowerCase()}"}
              ret = ret.replaceAll(/(^|\| )[^=|]+=/, '$1')
              return ret
          }
}

