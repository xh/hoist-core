/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.role

import grails.gorm.transactions.ReadOnly
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import io.xh.hoist.util.Timer

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
 * Hoist requires only three roles - "HOIST_ADMIN", "HOIST_ADMIN_READER" and "HOIST_ROLE_MANAGER" -
 * to support access to the built-in Admin Console and its backing endpoints. Custom application
 * implementations should take care to define and return these roles for suitable users.
 *
 * Note that `HoistUser.getRoles` and `HoistUser.hasRole` are the primary application
 * entry-points for verifying roles on a given user, reducing or eliminating any need to call an
 * implementation of this service directly.
 */
abstract class BaseRoleService extends BaseService {
    ConfigService configService

    private Timer timer
    protected Map<String, Set<String>> _allRoleAssignments

    static clearCachesConfigs = ['xhRoleModuleConfig']

    void init() {
        timer = createTimer(
            interval: { config.enabled ? config.refreshIntervalSecs as int * SECONDS : -1 },
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
     * when processing a request.
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
            if (users.contains(username)) ret << role
        }

        // For backward compatibility when not using built-in Hoist role management.
        if (!config.enabled && ret.contains('HOIST_ADMIN')) {
            ret << 'HOIST_ADMIN_READER'
            ret << 'HOIST_IMPERSONATOR'
        }

        return ret
    }

    /**
     * Return all users with a given role, as a simple set of usernames.
     *
     * Note that this default implementation does not validate that the usernames returned are in
     * fact active and enabled application users as per UserService. Apps may wish to do so -
     * the Hoist framework does not depend on it.
     *
     * Implementations should be careful to ensure this method doesn't throw, as doing so will
     * prevent the application from starting.
     */
    Set<String> getUsersForRole(String role) {
        return allRoleAssignments[role] ?: Collections.EMPTY_SET
    }

    /**
     * Return the configuration Map for this service with the following entries:
     *  enabled: boolean
     *  assignDirectoryGroups: boolean
     *  assignUsers: boolean
     *  refreshIntervalSecs: int
     */
    Map getConfig() {
        configService.getMap('xhRoleModuleConfig')
    }

    /**
     * Return Map of directory group names to either:
     *  a) Set<String> of assigned users
     *     OR
     *  b) String describing lookup error
     *  Applications should be careful to ensure this method doesn't throw, as doing so will prevent
     *  the application from starting.
     */
    protected Map getUsersForDirectoryGroups(Set<String> directoryGroups) {
        throw new UnsupportedOperationException('BaseRoleService.getUsersForDirectoryGroups not implemented.')
    }

    /**
     * Return a map of role names to assigned usernames.
     */
    @ReadOnly
    protected Map<String, Set<String>> generateRoleAssignments() {
        List<Role> roles = Role.list()
        Map usersForDirectoryGroups
        boolean assignUsers = config.assignUsers,
            assignDirectoryGroups = config.assignDirectoryGroups

        if (assignDirectoryGroups) {
            def groups = roles.collectMany(new HashSet()) { it.directoryGroups } as Set<String>
            if (groups) usersForDirectoryGroups = getUsersForDirectoryGroups(groups)
        }

        roles.collectEntries { role ->
            Set<String> users = new HashSet<String>()
            Map<RoleMember.Type, List<EffectiveMember>> members = role.resolveEffectiveMembers()

            if (assignUsers) {
                members[RoleMember.Type.USER]
                    .each { users.add(it.name) }
            }

            if (usersForDirectoryGroups) {
                members[RoleMember.Type.DIRECTORY_GROUP].each {
                    def groupUsers = usersForDirectoryGroups[it.name]
                    if (groupUsers instanceof Set) users.addAll(groupUsers)
                }
            }

            return [role.name, users]
        }
    }

    void clearCaches() {
        timer.forceRun()
        super.clearCaches()
    }

    protected void refreshRoleAssignments() {
        withDebug('Refreshing role assignments') {
            _allRoleAssignments = generateRoleAssignments()
        }
    }
}
