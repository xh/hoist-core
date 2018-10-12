/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONFormat
import static io.xh.hoist.util.Utils.configService
import static io.xh.hoist.util.Utils.roleService

/**
 * Core user properties required for Hoist.
 */
@CompileStatic
trait HoistUser implements JSONFormat {

    static boolean validateUsername(String username) {
        return username && username == username.toLowerCase() && !username.contains(' ')
    }

    abstract boolean isActive()
    abstract String getEmail()

    /**
     * Username for the user. Used for authentication, logging, tracking, and as a key for data
     * storage of preferences and user state. Apps must ensure each username is a unique to the
     * organization and that HoistUser.validateUsername(username) == true.
     */
    abstract String getUsername()

    String getDisplayName() {
        return username
    }

    /**
     * Roles are the primary source of application-level permissions and access.
     *
     * They are validated against server-side @Access interceptors to secure endpoints and are
     * serialized to JS clients for use in client-side logic.
     */
    Set<String> getRoles()  {
        return roleService.getRolesForUser(username)
    }

    boolean hasRole(String role)  {
        return roles.contains(role)
    }

    boolean hasAllRoles(String[] requiredRoles) {
        return roles.containsAll(requiredRoles)
    }

    /**
     * Gates are a lighter-weight concept, similar to roles, but sourced here from soft-config
     * and intended to restrict access to features under development or pending review.
     */
    boolean hasGate(String gate) {
        List gateUsers = configService.getStringList(gate)
        gateUsers.contains('*') || gateUsers.contains(username)
    }

    String toString() {username}

    boolean equals(Object other) {
        other instanceof HoistUser && Objects.equals(other.username, username)
    }

    int hashCode() {Objects.hashCode(username)}

    Map formatForJSON() {
        return [
                username: username,
                email: email,
                displayName: displayName,
                active: active
        ]
    }

}
