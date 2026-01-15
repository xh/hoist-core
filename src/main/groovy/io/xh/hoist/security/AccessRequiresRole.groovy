/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import java.lang.annotation.*

/**
 * Controller annotation to specify a required role to complete an action.
 * See related annotations:
 * @see AccessRequiresAllRoles
 * @see AccessRequiresAnyRole
 */
@Inherited
@Target([ElementType.METHOD, ElementType.TYPE])
@Retention(RetentionPolicy.RUNTIME)
@interface AccessRequiresRole {
    /** Role name to restrict access to.*/
    String value()
}
