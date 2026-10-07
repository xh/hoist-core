/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import groovy.transform.CompileStatic
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.SpecInfo

/**
 * Spock global extension, registered via `META-INF/services`, that:
 *
 *  - calls {@link HoistTestEnvironment#ensureInitialized} before any specification runs, so that
 *    adding `hoist-core-test` to an app's test classpath is sufficient to configure the Hoist
 *    system properties. Apps that prefer to be explicit can set the equivalent properties in
 *    their Gradle `test { systemProperty ... }` block instead - existing values are never replaced.
 *  - calls {@link HoistTestLogging#configure}, so test output is quiet and Hoist log messages
 *    render.
 *  - for specs implementing {@link HoistUnitTest}, registers and resets the Hoist framework beans
 *    before each feature, and clears the thread identity after it - in the same way Grails' own
 *    testing support extension wires `GrailsUnitTest` specs.
 */
@CompileStatic
class HoistSpockGlobalExtension implements IGlobalExtension {

    private final IMethodInterceptor setupInterceptor = { IMethodInvocation invocation ->
        ((HoistUnitTest) invocation.instance).setupHoistUnitTest()
        invocation.proceed()
    } as IMethodInterceptor

    private final IMethodInterceptor cleanupInterceptor = { IMethodInvocation invocation ->
        try {
            invocation.proceed()
        } finally {
            ((HoistUnitTest) invocation.instance).cleanupHoistUnitTest()
        }
    } as IMethodInterceptor

    @Override
    void start() {
        HoistTestEnvironment.ensureInitialized()
        HoistTestLogging.configure()
    }

    @Override
    void visitSpec(SpecInfo spec) {
        if (HoistUnitTest.isAssignableFrom(spec.reflection)) {
            // Attach to the top of the spec hierarchy, so beans are ready before any setup() runs.
            spec.topSpec.addSetupInterceptor(setupInterceptor)
            spec.topSpec.addCleanupInterceptor(cleanupInterceptor)
        }
    }
}
