/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.role

import grails.gorm.transactions.ReadOnly
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService

/**
 * Abstract base service for reporting on HoistUser <-> Role assignments. Hoist models roles as
 * simple strings, available to both server and client code.
 *
 * <p>Applications must define a concrete implementation of this service, named `RoleService`,
 * via one of the following approaches:
 * <ol>
 *   <li>Use Hoist's built-in {@link io.xh.hoist.role.provided.DefaultRoleService} - a
 *       ready-to-go, database-backed implementation with a dedicated Admin Console UI to manage
 *       roles and their memberships. See that class for details on how to opt in.</li>
 *   <li>Extend this class and implement its abstract methods to read roles from any custom
 *       source, without Hoist's built-in domain classes or Admin Console UI.</li>
 * </ol>
 *
 * <p>Custom implementations must keep the public API methods below fast and reliable, with local
 * caching as necessary, because Hoist can call them multiple times per request.
 *
 * <p>Hoist itself requires only three roles - "HOIST_ADMIN", "HOIST_ADMIN_READER" and
 * "HOIST_ROLE_MANAGER" - to gate access to the built-in Admin Console and its endpoints. Custom
 * implementations must define and return these roles for suitable users.
 *
 * <p>Note that {@link io.xh.hoist.user.HoistUser#getRoles} and
 * {@link io.xh.hoist.user.HoistUser#hasRole} are the primary entry points for checking the roles
 * of a given user. Applications rarely need to call this service directly.
 */
@CompileStatic
abstract class BaseRoleService extends BaseService {

    /** Map of all roles to their assigned users, as a set of usernames. */
    abstract Map<String, Set<String>> getAllRoleAssignments()

    /** All roles assigned to the given user(name). */
    abstract Set<String> getRolesForUser(String username)

    /** All users assigned to the given role, as a set of usernames. */
    abstract Set<String> getUsersForRole(String role)
}
