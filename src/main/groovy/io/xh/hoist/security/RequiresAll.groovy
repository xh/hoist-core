/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import java.lang.annotation.*

/**
 * Controller annotation to list roles required to execute any action.
 * Current user must have ALL roles listed to access.
 * Equivalent to @Access.
 */
@Inherited
@Target([ElementType.METHOD, ElementType.TYPE])
@Retention(RetentionPolicy.RUNTIME)
@interface RequiresAll {
    /** Array of role names required. */
    String[] value() default []
}
