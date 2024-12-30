/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.role

import grails.gorm.transactions.ReadOnly
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService

/**
 * Abstract base service for reporting on HoistUser <-> Role assignments, where roles are returned
 * as simple strings and made available to both server and client code.
 *
 * Applications must define or extend a concrete implementation of this service with the name
 * 'RoleService' via one of the following approaches:
 *
 * 1) Use Hoist's built-in {@link io.xh.hoist.role.provided.DefaultRoleService}. Hoist provides this
 *    implementation as a ready-to-go, database backed service that can be used in concert with a
 *    hoist-react Admin Console UI to manage roles and their memberships. See the docs on that
 *    service for additional details, including how to instantiate or inject within your app.
 *
 * 2) Extend this class and override its abstract methods to provide an entirely custom
 *    implementation, without leveraging Hoist's built-in domain classes or Admin Console UI.
 *
 * If building a custom implementation, please take care to ensure that the public API methods are
 * as fast and reliable as possible, with local caching as necessary, as they can be called multiple
 * times per request in quick succession.
 *
 * Hoist requires only three roles - "HOIST_ADMIN", "HOIST_ADMIN_READER" and "HOIST_ROLE_MANAGER" -
 * to support access to the built-in Admin Console and its backing endpoints. Custom application
 * implementations should take care to define and return these roles for suitable users.
 *
 * Note that `HoistUser.getRoles` and `HoistUser.hasRole` are the primary application entry-points
 * for verifying roles on a given user, reducing or eliminating any need to call an implementation
 * of this service directly.
 */
@CompileStatic
abstract class BaseRoleService extends BaseService {

    /** Return Map of roles to assigned users (as a set of usernames). */
    abstract Map<String, Set<String>> getAllRoleAssignments()

    /** Return all roles assigned to a given user(name). */
    abstract Set<String> getRolesForUser(String username)

    /** Return all users (as a set of usernames) with a given role. */
    abstract Set<String> getUsersForRole(String role)
}
