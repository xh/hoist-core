/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import groovy.transform.CompileStatic
import io.xh.hoist.user.BaseUserService
import io.xh.hoist.user.HoistUser

/**
 * In-memory {@link BaseUserService} for tests, holding whichever users have been added to it.
 *
 * Inherits the framework's real `impersonationTargetsForUser` logic. Registered by
 * {@link HoistTestContext} as the application's `userService` bean.
 */
@CompileStatic
class TestUserService extends BaseUserService {

    private final Map<String, HoistUser> users = Collections.synchronizedMap(new LinkedHashMap<String, HoistUser>())

    /** Add (or replace) users, keyed by username. Returns this service for chaining. */
    TestUserService add(HoistUser... newUsers) {
        newUsers.each { users[it.username] = it }
        this
    }

    @Override
    HoistUser find(String username) {
        users[username]
    }

    @Override
    List<HoistUser> list(boolean activeOnly) {
        synchronized (users) {
            users.values().findAll { !activeOnly || it.active } as List<HoistUser>
        }
    }

    /** Remove all users. */
    void clear() {
        users.clear()
    }
}
