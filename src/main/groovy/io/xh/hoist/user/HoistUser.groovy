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
     * Username for the user.
     * Used for authentication, logging, tracking, and as a key for data storage of preferences and user state.
     * Apps must ensure each username is a unique String and that HoistUser.validateUsername(username) == true.
     */
    abstract String getUsername()

    String getDisplayName() {
        return username
    }

    Set<String> getRoles()  {
        return Collections.emptySet()
    }

    boolean hasGate(String gate) {
        List gateUsers = configService.getStringList(gate)
        gateUsers.contains('*') || gateUsers.contains(username)
    }

    Map formatForJSON() {
        return [
                username: username,
                email: email,
                displayName: displayName,
                roles: roles,
                active: active
        ]
    }

}
