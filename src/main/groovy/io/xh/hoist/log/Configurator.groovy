package io.xh.hoist.log

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.spi.ContextAwareBase
import groovy.transform.CompileStatic
import io.xh.hoist.LogbackConfig

@CompileStatic
class Configurator extends ContextAwareBase implements ch.qos.logback.classic.spi.Configurator {

    @Override
    ExecutionStatus configure(LoggerContext loggerContext) {

        def clazz = Class.forName('io.xh.hoist.LogbackConfig')
        LogbackConfig instance = (clazz.getConstructor().newInstance() as LogbackConfig)

        instance.context = loggerContext
        instance.configLogging()

        //Added so that Spring Boot/Grails won't override this config with the Spring Boot defaults.
        loggerContext.putObject('org.springframework.boot.logging.LoggingSystem', new Object())
        return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY
    }
}