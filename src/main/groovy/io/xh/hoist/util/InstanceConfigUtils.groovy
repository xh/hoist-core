/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.util

import io.xh.hoist.AppEnvironment
import org.yaml.snakeyaml.Yaml

import static io.xh.hoist.util.Utils.isLocalDevelopment


/**
 * Utility for loading optional, file-based configuration properties once on startup and exposing
 * them to the application as a map.
 *
 * These are intended to be minimal, low-level configs that apply to a particular deployed instance
 * of the application and therefore are better sourced from a local file/volume vs. source code,
 * JavaOpts, or database-driven ConfigService entries.
 *
 * They are also typically used to provide the connection information and credentials for the
 * primary database connection itself, keeping that sensitive info out of the source code.
 *
 * This utility will consult the `-Dio.xh.hoist.instanceConfigFile` JavaOpt for the full path to one
 * of the following supported locations:
 *
 *      1) A YAML (.yml) file containing key value pairs.
 *      2) A directory containing multiple files, where the file names are loaded as config keys and
 *          their contents are read in as the values.
 *      3) A directory containing a YAML file with the name [appCode].yml.
 *
 * Option (1) is the default if the JavaOpt is not set, with a default location of
 * /etc/hoist/conf/[appCode].yml.
 *
 * Option (2) is intended for use in a Docker environment with configs/secrets mounted to a local
 * path within the container. (This is how Docker exposes configs to the runtime environment.)
 *
 * Option (3) is intended for builds that might or might not be deployed within Docker - allowing
 * for a single "directory style" JavaOpt value to be baked into the Tomcat container and resolve to
 * either a single file or to a directory of Docker configs.
 *
 * Note that all values will be read in and provided as Strings.
 *
 * This utility also establishes the `AppEnvironment`, sourcing it from (in priority order):
 *
 *      1) A `-Dio.xh.hoist.environment` JavaOpt, if provided.
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
            envString = optEnvString ?: confEnvString,
            env = AppEnvironment.parse(envString)

        if (!env) {
            if (isLocalDevelopment) {
                env = AppEnvironment.DEVELOPMENT
            } else {
                throw new RuntimeException("Cannot identify Hoist environment: '$envString'")
            }
        }

        _appEnvironment = env

        return ret
    }

    private static Map<String, String> loadFromConfigDir(File configDirectory) {
        File yamlFile = null
        Collection<File> configFiles = []
        String appCodeFilename = "${Utils.appCode}.yml"

        configDirectory.listFiles().each{File it ->
            if (!it.canRead() || it.isDirectory()) return
            if (it.name == appCodeFilename) {
                yamlFile = it
            } else {
                configFiles << it
            }
        }

        def ret
        if (yamlFile) {
            ret = loadFromYaml(yamlFile)
        } else {
            ret = configFiles.collectEntries{[it.name, it.text.trim()]} as Map<String, String>
            logLoadCount(ret, configDirectory)
        }
        return ret
    }

    private static Map<String, String> loadFromYaml(File configFile) {
        def ret = new Yaml().loadAs(configFile.newInputStream(), Map)
        logLoadCount(ret, configFile)
        return ret
    }

    private static void logLoadCount(Map configs, File configFile) {
        println "Loaded ${configs.size()} instanceConfigs from ${configFile.getAbsolutePath()}"
    }

}
