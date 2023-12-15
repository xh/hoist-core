/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import grails.gorm.transactions.ReadOnly
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.role.RoleMember
import io.xh.hoist.role.Role
import io.xh.hoist.util.Timer
import static io.xh.hoist.util.DateTimeUtils.SECONDS

/**
 * Abstract base service for maintaining a list of HoistUser <-> Role assignments, where roles
 * are returned as simple strings and made available to both server and client code.
 *
 * Applications MUST define a concrete implementation of this service with the name 'RoleService'.
 * Depending on the application's needs and any pre-existing or external infrastructure around role
 * management, applications may choose to implement this service in a variety of ways.
 *
 * Option 1 - use Hoist's built-in {@link Role} class and Admin UI to manage roles.
 *
 *      - Hoist provides a `Role` domain class to persist roles and their memberships to the
 *        app's primary database. Role members can include directly-assigned users, other roles
 *        (for role inheritance), and/or external directory groups (pointers to an Active Directory
 *        or LDAP instance - see below).
 *      - Roles and their memberships can be managed via the Hoist Admin Console.
 *      - With this option, an app-level implementation of this service does not need to override
 *        any of its protected methods - it can be an "empty" class that extends this base class.
 *
 * Option 1a - use Hoist Roles with external directoryGroup members.
 *
 *     - Hoist Roles can have members that are pointers to external directory groups.
 *     - Apps must implement `getUsersForDirectoryGroups()` on their RoleService to resolve these
 *       identifiers to actual usernames, e.g. by querying an external LDAP/AD instance.
 *     - Hoist's Admin Console UI can still be used to define Roles and add members in the form of
 *       directly assigned users (if desired), other roles, and external groups. The provided
 *       implementation will use the return from `getUsersForDirectoryGroups()` along with Role
 *       inheritance and direct user assignments to produce a full resolved set of users.
 *
 * Option 2 - use a custom implementation of RoleService.
 *
 *     - Hoist's built-in Role management (and Admin Console UI) can be disabled via config.
 *     - Apps must replace the default `generateRoleAssignments()` implementation entirely to read
 *       role assignments from a source of their choice.
 *     - If required for efficiency or any other app-specific handling, apps may also override
 *       `getRolesForUser()` and `getUsersForRole()`.
 *
 * Hoist requires only two roles - "HOIST_ADMIN" and "HOIST_ADMIN_READER" - to support access to the
 * built-in Admin Console and its backing endpoints. Custom application implementations should take
 * care to define and return these roles for suitable users.
 *
 * Note that {@link HoistUser#getRoles} and {@link HoistUser#hasRole} are the primary application
 * entry-point for verifying roles on a given user, reducing or eliminating any need to call an
 * implementation of this service directly.
 */
@CompileStatic
abstract class BaseRoleService extends BaseService {
    ConfigService configService

    private Timer timer
    private Map<String, Set<String>> _allRoleAssignments

    void init() {
        timer = createTimer(
            interval: { config.enabled ? config.refreshInterval as int * SECONDS : -1 },
            runFn: this.&refreshRoleAssignments,
            runImmediatelyAndBlock: true
        )
    }

    /**
     * Return Map of roles to assigned users.
     *
     * By default, this method returns a cached copy of the role assignments, refreshed on a
     * configurable interval.
     *
     * Applications may override this method to provide a custom implementation, but should take
     * care to provide an efficient / fast implementation as this can be queried multiple times
     * when processing a request, and is deliberately not cached on the HoistUser object.
     */
    Map<String, Set<String>> getAllRoleAssignments() {
        _allRoleAssignments
    }

    /**
     * Return all roles assigned to a given user(name).
     *
     * Also, note that this default implementation does not validate that the username provided is in
     * fact an active and enabled application user as per UserService. Apps may wish to do so -
     * the Hoist framework does not depend on it.
     */
    Set<String> getRolesForUser(String username) {
        Set<String> ret = new HashSet()
        allRoleAssignments.each { role, users ->
            if (users.contains(username)) ret.add(role)
        }
        if (ret.contains('HOIST_ADMIN')) { // todo - discuss whether we should keep this.
            ret.add('HOIST_ADMIN_READER')
            ret.add('HOIST_IMPERSONATOR')
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

    /**
     * Return Map of directory group names to assigned users.
     */
    protected Map<String, Set<String>> getUsersForDirectoryGroups(Set<String> directoryGroups) {
        if (!config.enableDirectoryGroups || !directoryGroups) return Collections.EMPTY_MAP
        throw new UnsupportedOperationException('BaseRoleService.getUsersForDirectoryGroups not implemented.')
    }

    /**
     * Return a map of role names to assigned usernames.
     * Return value is cached and refreshed on a configurable interval.
     */
    @ReadOnly
    protected Map<String, Set<String>> generateRoleAssignments() {
        List<Role> roles = Role.list()

        Set<String> directoryGroups = new HashSet<String>()
        roles.each { directoryGroups.addAll(it.directoryGroups) }
        Map<String, Set<String>> usersForDirectoryGroups = getUsersForDirectoryGroups(directoryGroups)

        roles.collectEntries { role ->
            Set<String> users = new HashSet<String>()
            Map<RoleMember.Type, List<EffectiveMember>> members = role.resolveEffectiveMembers()

            members[RoleMember.Type.USER]
                .each { users.add(it.name) }

            members[RoleMember.Type.DIRECTORY_GROUP]
                .each { users.addAll(usersForDirectoryGroups[it.name] ?: Collections.EMPTY_SET) }

            return [role.name, users]
        }
    }

    @Override
    void clearCaches() {
        timer.forceRun()
        super.clearCaches()
    }

    protected void refreshRoleAssignments() {
        withDebug('Refreshing role assignments') {
            _allRoleAssignments = generateRoleAssignments()
        }
    }

    private Map getConfig() {
        return configService.getMap('xhRoleServiceConfig')
    }
}
