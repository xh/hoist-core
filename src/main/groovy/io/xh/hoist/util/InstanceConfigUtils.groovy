/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.util

import io.xh.hoist.AppEnvironment
import org.yaml.snakeyaml.Yaml


/**
 * Utility for loading configuration properties from an external YAML file once on startup and
 * exposing them to the application as a map.
 *
 * These are intended to be minimal, low-level configs that apply to a particular deployed instance
 * of the application and therefore are better sourced from a local file/volume vs. source code,
 * JavaOpts, or database-driven ConfigService entries.
 *
 * The one key guaranteed to be populated is `environment`, which represents the current Hoist
 * AppEnvironment. (This key can also be set via a JavaOpt for backwards compatibility.)
 *
 * Expected use-cases (apart from environment) could include configs for the primary database
 * connection itself (e.g. host/username/password).
 *
 * Note that this class is *not* available for use in application.groovy, as that file is read
 * and processed prior to compilation. It can however be read from within a conf/runtime.groovy
 * file, which can also be used to set core Grails configuration options (as long as they are not
 * needed for any CLI commands you are using). See https://docs.grails.org/latest/guide/conf.html
 *
 * The location of the config file itself is specified via a -Dio.xh.hoist.instanceConfigFile
 * JavaOpt (if the default path below is not satisfactory).
 */
class InstanceConfigUtils {

    final static Map instanceConfig = readInstanceConfig()

    static getInstanceConfig(String key) {
        return instanceConfig[key]
    }

    static AppEnvironment getAppEnvironment() {
        return instanceConfig.environment
    }


    //------------------------
    // Implementation
    //------------------------
    private static Map readInstanceConfig() {
        def ret = [:]

        // Attempt to load external config file - but do not require one.
        try {
            def configFilename = System.getProperty('io.xh.hoist.instanceConfigFile') ?: "/etc/hoist/conf/${Utils.appCode}.yml",
                configFile = new File(configFilename)

            if (configFile.exists()) {
                ret = new Yaml().loadAs(configFile.newInputStream(), Map)
            }
        } catch (ignored) {}

        // Ensure environment key populated - source from (in priority order):
        // JavaOpt, YAML config, or fallback to 'Development'.
        def optEnv = System.getProperty('io.xh.hoist.environment')
        ret.environment = optEnv ?: (ret.environment ?: 'Development')

        // Parse environment into enum, falling back to Development again if it can't be parsed.
        ret.environment = AppEnvironment.parse(ret.environment) ?: AppEnvironment.DEVELOPMENT

        return ret
    }

}
