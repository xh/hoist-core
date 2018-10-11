/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService

import static java.util.Collections.emptyMap

/**
 * Abstract base service for maintaining a list of HoistUser <-> role assignments, where roles
 * are returned as simple strings and made available to both server and client code.
 *
 * Applications must define a concrete implementation of this service with the name 'RoleService'.
 * Roles should be defined and sourced based on the app's particular needs.
 *
 * By way of example, implementations could:
 *      + Query roles from a database, maintained by this app or externally.
 *      + Query LDAP / AD group memberships, and resolve those to roles.
 *      + Leverage configService to provide quick / built-in storage for role mappings.
 *
 * Hoist has a requirement for only one special role - "HOIST_ADMIN" - which grants access to the
 * built-in admin functions and enables user impersonation via IdentityService.
 *
 * Note that the HoistUser getRoles() and hasRole() methods can serve as the primary application
 * entry-point for verifying roles on a given user, reducing or eliminating any need to call this
 * service directly.
 */
@CompileStatic
abstract class BaseRoleService extends BaseService {

    /**
     * Map of roles to assigned users.
     * Applications should take care to provide an efficient / fast implementation.
     */
    Map<String, Set<String>> getAllRoleAssignments() {
        return emptyMap()
    }

    Set<String> getRolesForUser(String username) {
        return allRoleAssignments
            .findAll{role, users -> users.contains(username)}
            .keySet()
    }

    Set<String> getUsersForRole(String role) {
        return allRoleAssignments[role] ?: new HashSet<String>()  // TODO - how to use emptySet here?
    }

}