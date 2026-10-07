/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import org.spockframework.runtime.extension.ExtensionAnnotation

import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Inherited
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Spock annotation that installs a fresh {@link HoistTestContext} before each feature and closes
 * it afterwards, clearing any logged-in user. Access the context via
 * {@link HoistTestContext#getCurrent}.
 *
 * Extend {@link HoistSpec} to get this behavior plus convenience methods, or apply this annotation
 * directly to a specification that already has another base class or trait:
 *
 * <pre>
 * {@code @HoistTest}
 * class MyServiceSpec extends Specification {
 *     def 'reads config'() {
 *         given:
 *         def hoist = HoistTestContext.current
 *         ...
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.TYPE])
@Inherited
@Documented
@ExtensionAnnotation(HoistTestExtension)
@interface HoistTest {}
