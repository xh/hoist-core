package io.xh.hoist.configuration

import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static io.xh.hoist.util.Utils.withDelegate

/**
 * Default Application config.
 *
 * Main entry point to be called from runtime.groovy
 */
class RuntimeConfig {

    static void defaultConfig(Script script) {
        withDelegate(script) {
            grails.serverURL = getInstanceConfig('serverURL')

        }
    }
}
