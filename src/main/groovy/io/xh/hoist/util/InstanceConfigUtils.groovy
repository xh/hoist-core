/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.util

import groovy.transform.CompileStatic
import groovy.transform.Memoized

import static com.fasterxml.jackson.databind.PropertyNamingStrategies.UpperSnakeCaseStrategy
import static com.fasterxml.jackson.databind.PropertyNamingStrategies.UPPER_SNAKE_CASE
import io.xh.hoist.AppEnvironment
import org.yaml.snakeyaml.Yaml

import static io.xh.hoist.util.Utils.isLocalDevelopment


/**
 * Utility for loading configuration properties once on startup and exposing them to the application.
 *
 * These are intended to be minimal, low-level configs that apply to a particular deployed instance
 * of the application. They are also typically used to provide the connection information and
 * credentials for the primary database connection itself, keeping that sensitive info out of the
 * source code.
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
 *      2) Environment variable with key `APP_[APP_CODE]_ENVIRONMENT`, if provided.
 *      3) Config file/directory entry with key `environment`, if provided.
 *      4) Fallback to Development, if not otherwise specified.
 *
 * The AppEnvironment is made available via its own property. (The `io.xh.hoist.util.Utils` method
 * is the expected entry point for most app code, anyway.)
 *
 * Note that this class is *not* available for use in application.groovy, as that file is read
 * and processed prior to compilation. It can however be read from within a conf/runtime.groovy
 * file, which can also be used to set core Grails configuration options (as long as they are not
 * needed for any CLI commands you are using). See https://docs.grails.org/latest/guide/conf.html
 */
@CompileStatic
class InstanceConfigUtils {

    final static AppEnvironment appEnvironment = readAppEnvironment()

    private final static Map<String, String> configs = readConfigs()

    /**
     * Retrieve a configuration value by key sourced from (in priority order):
     *     1) Environment variable with upper snake-case key `APP_[APP_CODE]_[KEY]`, if provided.
     *     2) Config file/directory entry with key `[key]`, if provided.
     */
    @Memoized
    static String getInstanceConfig(String key) {
        return getEnvVar(key) ?: configs[key]
    }


    //------------------------
    // Implementation
    //------------------------

    private static Map<String, String> readConfigs() {
        Map<String, String> ret = [:]

        // Attempt to load external config file - but do not strictly require one. Warnings about
        // missing/unreadable configs are output via println as the logging system itself relies on
        // this class to determine its root path.
        String configFilename = System.getProperty('io.xh.hoist.instanceConfigFile') ?: "/etc/hoist/conf/${Utils.appCode}.yml"
        try {
            File configFile = new File(configFilename)

            if (configFile.exists()) {
                ret = configFile.isDirectory() ? loadFromConfigDir(configFile) : loadFromYaml(configFile)
            } else {
                println "InstanceConfig file not found | looked for $configFilename"
            }
        } catch (Throwable t) {
            println "ERROR - InstanceConfig file could not be parsed | $configFilename | $t.message"
        }

        return ret
    }

    private static AppEnvironment readAppEnvironment() {
        def optEnvString = System.getProperty('io.xh.hoist.environment') ?: getEnvVar('environment'),
            envString = optEnvString ?: configs.environment,
            env = AppEnvironment.parse(envString)

        if (!env) {
            if (isLocalDevelopment) {
                env = AppEnvironment.DEVELOPMENT
            } else {
                throw new RuntimeException("Cannot identify Hoist environment: '$envString'")
            }
        }

        return env
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

    private static String getEnvVar(String key) {
        return System.getenv("APP_${toSnakeCase(Utils.appCode)}_${toSnakeCase(key)}")
    }

    private static String toSnakeCase(String s) {
        return ((UpperSnakeCaseStrategy) UPPER_SNAKE_CASE).translate(s).replace('-', '_')
    }
}
