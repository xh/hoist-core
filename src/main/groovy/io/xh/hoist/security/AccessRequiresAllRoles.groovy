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
 */
@Inherited
@Target([ElementType.METHOD, ElementType.TYPE])
@Retention(RetentionPolicy.RUNTIME)
@interface AccessRequiresAllRoles {
    /** Array of role names, all of which are required for access. */
    String[] value()
}
