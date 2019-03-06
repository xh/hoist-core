/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.util

import io.xh.hoist.AppEnvironment
import org.yaml.snakeyaml.Yaml


/**
 * Utility for loading optional, file-based configuration properties once on startup and exposing
 * them to the application as a map. This utility can be pointed to one of two kinds of files:
 *
 *      + A single YAML (.yml) file containing key value pairs (the default).
 *
 *      + A directory containing multiple files, where the file names are the keys and the
 *        file contents are the values. This is intended for use in a Docker environment with
 *        configs/secrets mounted to a local path within the container.
 *
 * To support this second form, all values will be read in and provided as Strings.
 *
 * These are intended to be minimal, low-level configs that apply to a particular deployed instance
 * of the application and therefore are better sourced from a local file/volume vs. source code,
 * JavaOpts, or database-driven ConfigService entries. Expected use-cases include configs for the
 * primary database connection itself (e.g. host/username/password).
 *
 * This utility also establishes the `AppEnvironment`, sourcing it from (in priority order):
 *
 *      1) -Dio.xh.hoist.environment JavaOpt, if provided.
 *      2) Config file/directory entry with key `environment`, if provided.
 *      3) Fallback to Development, if not otherwise specified.
 *
 * The AppEnvironment is made available via its own getter that returns the proper Enum, and is
 * deliberately removed from the instanceConfig map. (The `io.xh.hoist.util.Utils` method is the
 * expected entry point for most app code, anyway.)
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

    final static Map<String, String> instanceConfig = readInstanceConfig()
    private static AppEnvironment _appEnvironment

    static String getInstanceConfig(String key) {
        return instanceConfig[key]
    }

    static AppEnvironment getAppEnvironment() {
        return _appEnvironment
    }


    //------------------------
    // Implementation
    //------------------------
    private static Map<String, String> readInstanceConfig() {
        def ret = [:]

        // Attempt to load external config file - but do not strictly require one. Warnings about
        // missing/unreadable configs are output via println as the logging system itself relies on
        // this class to determine its root path.
        def configFilename = System.getProperty('io.xh.hoist.instanceConfigFile') ?: "/etc/hoist/conf/${Utils.appCode}.yml"
        try {
            def configFile = new File(configFilename)

            if (configFile.exists()) {
                ret = configFile.isDirectory() ? loadFromConfigDir(configFile) : loadFromYaml(configFile)
            } else {
                println "WARNING - InstanceConfig file not found | looked for $configFilename"
            }
        } catch (Throwable t) {
            println "ERROR - InstanceConfig file could not be parsed | $configFilename | $t.message"
        }

        // Populate environment, popping it off the map if provided via config. Priority as documented above.
        def optEnvString = System.getProperty('io.xh.hoist.environment'),
            confEnvString = ret.remove('environment'),
            envString = optEnvString ?: (confEnvString ?: 'Development')

        _appEnvironment = AppEnvironment.parse(envString) ?: AppEnvironment.DEVELOPMENT

        return ret
    }

    private static Map<String, String> loadFromConfigDir(File configDirectory) {
        def ret = [:]
        configDirectory.listFiles().each{File it ->
            if (it.canRead() && !it.isDirectory()) {
                ret[it.name] = it.text.trim()
            }
        }
        return ret
    }

    private static Map<String, String> loadFromYaml(File configFile) {
        return new Yaml().loadAs(configFile.newInputStream(), Map)
    }

}
