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
 * The three member lists ({@link #users}, {@link #directoryGroups}, {@link #grantedToRoles}) all
 * name who <b>receives</b> this role. They do not grant anything to the members.
 *
 * Members are seeded only when the role is first created. Existing roles are never modified.
 *
 * Provides IDE autocomplete and compile-time validation for role definitions.
 */
@MapConstructor
class RoleSpec {
    /** Unique role name, e.g. `APP_ADMIN`. */
    String name

    /** Optional grouping label for the Admin Console role manager. */
    String category

    /** Optional description of the access this role grants. */
    String notes

    /** Usernames granted this role directly. */
    List<String> users

    /** Directory groups whose members are granted this role. */
    List<String> directoryGroups

    /**
     * Other roles this role is granted to, i.e. "holders of these roles also get this role".
     * The listed roles are <b>members</b> of this role, not roles granted by it.
     *
     * <p>Example: `new RoleSpec(name: 'APP_USER', grantedToRoles: ['APP_ADMIN'])` gives every
     * `APP_ADMIN` the `APP_USER` role. It does not give `APP_USER` holders `APP_ADMIN`. Grants
     * are transitive.
     */
    List<String> grantedToRoles

    /**
     * @deprecated Use {@link #grantedToRoles}, which has identical meaning.
     *     Targeted for removal in v47.
     */
    @Deprecated
    List<String> roles
}
