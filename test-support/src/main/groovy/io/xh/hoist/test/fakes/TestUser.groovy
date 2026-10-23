/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test.fakes

import groovy.transform.CompileStatic
import io.xh.hoist.user.HoistUser

/**
 * Simple in-memory {@link HoistUser} for tests, with a fixed set of roles.
 *
 * Unlike an app's real user class, this does not consult the application's `RoleService` - its
 * roles are exactly those supplied here, so `hasRole()`, `isHoistAdmin` and the other role-based
 * checks inherited from `HoistUser` work with no further setup.
 *
 * <pre>
 * def admin = new TestUser('alice', ['HOIST_ADMIN'])
 * def reader = new TestUser('bob')
 * </pre>
 */
@CompileStatic
class TestUser implements HoistUser, Serializable {

    String username
    String email
    String displayName
    boolean active = true
    Set<String> roles = new LinkedHashSet<String>()

    /**
     * @param username lowercase, space-free username
     * @param roles roles held by this user
     */
    TestUser(String username, Collection<String> roles = []) {
        this.username = username
        this.email = "${username}@example.com".toString()
        this.roles = new LinkedHashSet<String>(roles)
    }

    /** Fixed roles of this user - does not consult any RoleService. */
    @Override
    Set<String> getRoles() {
        roles
    }

    /** Explicit display name, defaulting to the username. */
    @Override
    String getDisplayName() {
        displayName ?: username
    }
}
