/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.role.provided

import groovy.transform.MapConstructor

/**
 * Typed specification for a required role to be created by
 * {@link DefaultRoleService#ensureRequiredRolesCreated}.
 *
 * Mirrors the seedable fields of {@link Role} plus optional initial member assignments.
 *
 * Provides IDE autocomplete and compile-time validation for role definitions.
 */
@MapConstructor
class RoleSpec {
    String name
    String category
    String notes
    List<String> users
    List<String> directoryGroups
    List<String> roles
}
