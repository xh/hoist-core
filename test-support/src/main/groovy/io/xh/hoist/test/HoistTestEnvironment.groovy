/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import groovy.transform.CompileStatic

/**
 * Sets the JVM system properties Hoist needs in order to run service code outside of a running
 * application.
 *
 * Several Hoist framework classes read process-wide settings once, in static initializers, and
 * fail if no application code or environment is available - which is always the case in a unit
 * test JVM. This class supplies safe test values for the four relevant properties:
 *
 *  - `io.xh.hoist.environment` = `Test`
 *  - `io.xh.hoist.instanceConfigFile` = a generated temp YAML file with `multiInstanceEnabled: 'false'`,
 *     which also stops a developer machine from reading `/etc/hoist/conf/{appCode}.yml`.
 *  - `info.xh.appCode` = {@link #DEFAULT_APP_CODE}
 *  - `info.app.version` = {@link #DEFAULT_APP_VERSION}
 *
 * Each property is set only if absent, so an app (or its build) can override any of them. Note that
 * the `info.*` values are only a fallback - an app `grails.build.info` file on the test classpath
 * takes precedence, as do environment variables of the form `APP_{APPCODE}_{KEY}`.
 *
 * This runs automatically, before any specification, via {@link HoistSpockGlobalExtension} -
 * apps do not normally need to call it. It must complete before `ClusterService` or
 * `InstanceConfigUtils` are initialized, as those capture their values statically.
 */
@CompileStatic
final class HoistTestEnvironment {

    /** App code applied when the application does not provide one. */
    static final String DEFAULT_APP_CODE = 'hoist-test'

    /** App version applied when the application does not provide one. */
    static final String DEFAULT_APP_VERSION = '0.0-TEST'

    private static boolean initialized = false
    private static File instanceConfigFile

    private HoistTestEnvironment() {}

    /** Set the test system properties, if not already set. Idempotent and thread-safe. */
    static synchronized void ensureInitialized() {
        if (initialized) return
        def props = System.properties
        props.putIfAbsent('io.xh.hoist.environment', 'Test')
        props.putIfAbsent('io.xh.hoist.instanceConfigFile', getInstanceConfigFile().absolutePath)
        props.putIfAbsent('info.xh.appCode', DEFAULT_APP_CODE)
        props.putIfAbsent('info.app.version', DEFAULT_APP_VERSION)
        initialized = true
    }

    /**
     * Temp YAML instance config file, generated on first access, that disables multi-instance
     * clustering so that Caches, CachedValues and Timers use their simple local implementations.
     */
    static synchronized File getInstanceConfigFile() {
        if (!instanceConfigFile) {
            instanceConfigFile = File.createTempFile('hoist-test-instance-config', '.yml')
            instanceConfigFile.deleteOnExit()
            instanceConfigFile.text = "multiInstanceEnabled: 'false'\n"
        }
        instanceConfigFile
    }
}
