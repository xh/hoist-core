/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.role.Role

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
 * Note that the HoistUser `getRoles()` and `hasRole()` methods serve as the primary application
 * entry-point for verifying roles on a given user, reducing or eliminating any need to call an
 * implementation of this service directly.
 */
@CompileStatic
abstract class BaseRoleService extends BaseService {

    Set<String> listUsersForDirectoryGroup(String directoryGroup) { // TODO - make async
        Collections.EMPTY_SET
    }

    /**
     * Map of roles to assigned users.
     *
     * Applications should take care to provide an efficient / fast implementation as this can be
     * queried multiple times when processing a request, and is deliberately not cached on the
     * HoistUser object.
     */
    Map<String, Set<String>> getAllRoleAssignments() {
        Role.list().collectEntries { role ->
            Set<String> users = new HashSet<String>()

            users.addAll(role.listEffectiveUsers().collect { it.name as String })
            users.addAll(role.listEffectiveDirectoryGroups().collect {
                listUsersForDirectoryGroup(it.name as String)
            }.flatten() as String[])

            return [role.name, users]
        }
    }

    /**
     * Return all roles assigned to a given user(name).
     *
     * Also, note that this default implementation does not validate that the username provided is in
     * fact an active and enabled application user as per UserService. Apps may wish to do so -
     * the Hoist framework does not depend on it.
     *
     * Note that this method does implement some basic logic on built-in role inheritance.  Therefore any
     * implementation overrides should typically call the super method.
     */
    Set<String> getRolesForUser(String username) {
        Set<String> ret = new HashSet()
        allRoleAssignments.each { role, users ->
            if (users.contains(username)) ret.add(role)
        }
        return ret
    }


    /**
     * Return all users with a given role, as a simple set of usernames.
     *
     * Note that this default implementation does not validate that the usernames returned are in
     * fact active and enabled application users as per UserService. Apps may wish to do so -
     * the Hoist framework does not depend on it.
     */
    Set<String> getUsersForRole(String role) {
        return allRoleAssignments[role] ?: Collections.EMPTY_SET
    }
}
