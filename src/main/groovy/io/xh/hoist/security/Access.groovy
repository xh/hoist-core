/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import java.lang.annotation.*

/**
 * Controller annotation to list roles required to execute any action.
 * Current user must have all roles listed.
 * @see AccessInterceptor
 */
@Target([ElementType.METHOD, ElementType.TYPE])
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@interface Access {
    String[] value() default []
}
