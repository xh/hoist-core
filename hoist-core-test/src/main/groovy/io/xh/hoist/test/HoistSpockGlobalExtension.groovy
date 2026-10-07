/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import groovy.transform.CompileStatic
import org.spockframework.runtime.extension.IGlobalExtension

/**
 * Spock global extension, registered via `META-INF/services`, that calls
 * {@link HoistTestEnvironment#ensureInitialized} before any specification runs.
 *
 * Adding `hoist-core-test` to an app's test classpath is therefore sufficient to configure the
 * Hoist system properties. Apps that prefer to be explicit can set the equivalent properties in
 * their Gradle `test { systemProperty ... }` block instead - existing values are never replaced.
 */
@CompileStatic
class HoistSpockGlobalExtension implements IGlobalExtension {

    @Override
    void start() {
        HoistTestEnvironment.ensureInitialized()
    }
}
