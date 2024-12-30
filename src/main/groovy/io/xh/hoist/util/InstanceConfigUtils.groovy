/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
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
 * Utility for loading low-level configuration on application startup.
 *
 * These are intended to be minimal, low-level configs that apply to a particular deployed instance
 * of the application. They are typically used to provide the host and credentials for the primary
 * database connection itself, keeping that environment-specific and also sensitive info out of the
 * source code.
 *
 * There are three ways to provide these configs:
 *    1) Via system environment variables, with keys matching `APP_[APP_CODE]_[KEY]`, where `[KEY]`
 *       is the config key referenced in code in upper snake-case. E.g. for the XH demo app Toolbox,
 *       an environment variable of `APP_TOOLBOX_DB_HOST` could be read from this util via
 *       `getInstanceConfig('dbHost')`.
 *    2) Via a YAML file containing key value pairs.
 *    3) Via a directory containing multiple files, where the file names are loaded as config keys
 *       and their contents are read in as the values.
 *
 * For options 2+3, the `-Dio.xh.hoist.instanceConfigFile` JavaOpt can be used to specify the full
 * path to one of the following:
 *      1) A YAML file.
 *      2) A directory containing a YAML file with the name [appCode].yml.
 *      3) A directory containing multiple files, where the file names are loaded as config keys
 *         and their contents are read in as the values. Intended for use in a Docker environment
 *         with configs/secrets mounted to a local path within the container.
 *
 * If the JavaOpt is not set, this util will check for a file in the default location of
 * `/etc/hoist/conf/[appCode].yml`.
 *
 * If both an environment variable and a config file/directory entry are provided for the same key,
 * the environment variable will take precedence.
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

    private final static Map<String, String> configs = readConfigs()
    final static AppEnvironment appEnvironment = readAppEnvironment()

    /**
     * Retrieve a configuration value by key sourced from (in priority order):
     *     1) Environment variable with upper snake-case key `APP_[APP_CODE]_[KEY]`, if set.
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

        // Informational only - note if we have any env vars that match our pattern.
        def envVars = getInstConfigEnvVars()
        if (envVars) {
            println "InstanceConfigUtils [INFO] | Found ${envVars.size()} environment variables matching InstanceConfig pattern"
        }

        // Attempt to load external config file - but do not require one, as instance configs might
        // not be needed, or might be supplied via env vars.
        String configFilename = System.getProperty('io.xh.hoist.instanceConfigFile') ?: "/etc/hoist/conf/${Utils.appCode}.yml"
        try {
            File configFile = new File(configFilename)

            if (configFile.exists()) {
                ret = configFile.isDirectory() ? loadFromConfigDir(configFile) : loadFromYaml(configFile)
            } else if (!envVars) {
                println "InstanceConfigUtils [WARN] | YAML-based InstanceConfig file not found and no matching env vars set | looked for $configFilename"
            }
        } catch (Throwable t) {
            println "InstanceConfigUtils [ERROR] | InstanceConfig file found but could not be parsed | $configFilename | $t.message"
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
        println "InstanceConfigUtils [INFO] | Loaded ${configs.size()} configs from ${configFile.getAbsolutePath()}"
    }

    private static String getEnvVar(String key) {
        return System.getenv("${envVarPrefix}${toSnakeCase(key)}")
    }

    private static Map getInstConfigEnvVars() {
        return System.getenv().findAll { k, v -> k.startsWith(envVarPrefix) }
    }

    private static String getEnvVarPrefix() {
        return "APP_${toSnakeCase(Utils.appCode)}_"
    }

    private static String toSnakeCase(String s) {
        return ((UpperSnakeCaseStrategy) UPPER_SNAKE_CASE).translate(s).replace('-', '_')
    }
}
