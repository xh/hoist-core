/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import java.lang.annotation.*

/**
 * Controller annotation to indicate that any user can access, regardless of roles.
 * @see AccessInterceptor
 */
@Inherited
@Target([ElementType.METHOD, ElementType.TYPE])
@Retention(RetentionPolicy.RUNTIME)
@interface AccessAll {

}
