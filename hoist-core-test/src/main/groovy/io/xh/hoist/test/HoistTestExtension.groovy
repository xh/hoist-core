/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import groovy.transform.CompileStatic
import org.spockframework.runtime.extension.IAnnotationDrivenExtension
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.SpecInfo

/**
 * Implementation of {@link HoistTest}. Wraps each feature's setup and cleanup so that a
 * {@link HoistTestContext} is installed before any fixture or feature code runs, and closed after.
 *
 * Interceptors, rather than `setup()`/`cleanup()` methods, are used so that specifications are
 * free to declare their own fixture methods. Both steps are idempotent, so the extension is safe
 * if Spock visits the annotation on both a base specification and its subclass.
 */
@CompileStatic
class HoistTestExtension implements IAnnotationDrivenExtension<HoistTest> {

    @Override
    void visitSpecAnnotation(HoistTest annotation, SpecInfo spec) {
        spec.addSetupInterceptor { IMethodInvocation invocation ->
            HoistTestContext.install()
            invocation.proceed()
        }
        spec.addCleanupInterceptor { IMethodInvocation invocation ->
            try {
                invocation.proceed()
            } finally {
                HoistTestContext.current?.close()
            }
        }
    }
}
