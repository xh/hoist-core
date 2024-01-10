/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.role.provided

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.role.BaseRoleService
import io.xh.hoist.util.DateTimeUtils
import io.xh.hoist.util.Timer

import static io.xh.hoist.role.provided.RoleMember.Type.DIRECTORY_GROUP
import static io.xh.hoist.role.provided.RoleMember.Type.USER

/**
 * Optional concrete implementation of BaseRoleService for applications that wish to leverage
 * Hoist's built-in, database-backed Role management and its associated Admin Console UI.
 *
 * Applications using this default implementation must either:
 *
 *  1) Define a `RoleService` class that extends this class. Allows for overriding its protected
 *     methods to customize behavior, including the option to implement `getUsersForDirectoryGroups`
 *     to resolve external directory group memberships (see below).
 *
 *  2) Register this class directly as `roleService` via `grails-app/conf/spring/resources.groovy`,
 *     assuming neither directory group support nor any further customizations are required.
 *
 * When opting-in to this service:
 *
 *  - Hoist provides a {@link Role} domain class to persist roles and their memberships to the
 *    app's primary database. Role members can include directly-assigned users, other roles (for
 *    role inheritance), and/or external directory groups (see below).

 *  - Admins can view + manage roles and their memberships via a fully-featured Hoist React Admin
 *    Console UI (requires hoist-react >= v60).
 *
 *  - Roles and their memberships, including any resolved directory group memberships, are cached by
 *    this service for efficient querying, with a configurable refresh interval.
 *
 * This service can assign role memberships based on "directory groups" - pointers to groups
 * maintained within a corporate Active Directory, LDAP, or equivalent installation. Roles
 * themselves must still be created + managed in the local app database via the Admin Console,
 * but in addition to (or instead of) assigning users directly as members, admins can assign
 * directory groups to roles. Users within those groups will then inherit membership in the Role.
 *
 * Apps wishing to use this feature must:
 *
 *  1) Extend this service and implement {@link BaseRoleService#getUsersForDirectoryGroups}.
 *
 *  2) Activate directory group support via `xhRoleModuleConfig.assignDirectoryGroups` (see below).
 *
 * Certain aspects of this service and its Admin Console UI are soft-configurable via a JSON
 * `xhRoleModuleConfig`. This service will create this config entry if not found on startup.
 *
 * The following config options are supported as keys in this map:
 *
 *  - assignDirectoryGroups: boolean - true to enable assignment of external directory groups as
 *    role members. Requires an in-code implementation of `getUsersForDirectoryGroups()`. Controls
 *    visibility of the directory group membership list in the "Edit Role" admin dialog.
 *
 *  - assignUsers: boolean - true to enable direct assignment of users as role members. Disable to
 *    *required* users to be assigned via directory groups only. Controls visibility of the user
 *    membership list in the "Edit Role" admin dialog.
 *
 *  - refreshIntervalSecs: int - number of seconds between refreshes of the role membership cache.
 *
 *
 * @see BaseRoleService for additional documentation on the core RoleService API and its usage.
 */
class DefaultRoleService extends BaseRoleService {

    def configService,
        roleAdminService

    private Timer timer
    protected Map<String, Set<String>> _allRoleAssignments
    protected Map<String, Set<String>> _roleAssignmentsByUser

    static clearCachesConfigs = ['xhRoleModuleConfig']

    void init() {
        ensureRequiredConfigAndRolesCreated()

        timer = createTimer(
            interval: { config.refreshIntervalSecs as int * DateTimeUtils.SECONDS },
            runFn: this.&refreshRoleAssignments,
            runImmediatelyAndBlock: true
        )
    }

    Map<String, Set<String>> getAllRoleAssignments() {
        _allRoleAssignments
    }


    Set<String> getRolesForUser(String username) {
        if (!_roleAssignmentsByUser.containsKey(username)) {
            Set<String> userRoles = new HashSet()
            allRoleAssignments.each { role, users ->
                if (users.contains(username)) userRoles << role
            }

            _roleAssignmentsByUser[username] = Collections.unmodifiableSet(userRoles)
        }

        _roleAssignmentsByUser[username]
    }

    /**
     * Note that this default implementation does not validate that the usernames returned are
     * active and enabled application users as per `UserService`.
     *
     * Apps may wish to do so - Hoist does not depend on it.
     */
    Set<String> getUsersForRole(String role) {
        allRoleAssignments[role] ?: Collections.EMPTY_SET
    }

    /** See the class-level documentation comment for support configuration. */
    Map getConfig() {
        configService.getMap('xhRoleModuleConfig')
    }

    //------------------
    // Implementation
    //------------------
    protected void refreshRoleAssignments() {
        withDebug('Refreshing role assignments') {
            _allRoleAssignments = generateRoleAssignments()
            _roleAssignmentsByUser = new HashMap()
        }
    }

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
                members[USER].each { users.add(it.name) }
            }

            if (usersForDirectoryGroups) {
                members[DIRECTORY_GROUP].each {
                    def groupUsers = usersForDirectoryGroups[it.name]
                    if (groupUsers instanceof Set) users.addAll(groupUsers)
                }
            }

            return [role.name, users]
        }
    }

    /**
     * Ensure that the required soft-config entry for this service has been created, along with a
     * minimal set of required Hoist roles.
     */
    protected void ensureRequiredConfigAndRolesCreated() {
        configService.ensureRequiredConfigsCreated([
            xhRoleModuleConfig: [
                valueType: 'json',
                defaultValue: [
                    assignUsers: true,
                    assignDirectoryGroups: false,
                    refreshIntervalSecs: 30,
                    infoTooltips: [
                        users: null,
                        directoryGroups: null,
                        roles: null
                    ]
                ],
                groupName: 'xh.io',
                note: 'Configures built-in role management via DefaultRoleService.'
            ]
        ])

        roleAdminService.ensureRequiredRolesCreated([
            [
                name: 'HOIST_ADMIN',
                category: 'Hoist',
                notes: 'Hoist Admins have full access to all Hoist Admin tools and functionality.'
            ],
            [
                name: 'HOIST_ADMIN_READER',
                category: 'Hoist',
                notes: 'Hoist Admin Readers have read-only access to all Hoist Admin tools and functionality.',
                roles: ['HOIST_ADMIN']
            ],
            [
                name: 'HOIST_IMPERSONATOR',
                category: 'Hoist',
                notes: 'Hoist Impersonators can impersonate other users.',
                roles: ['HOIST_ADMIN']
            ],
            [
                name: 'HOIST_ROLE_MANAGER',
                category: 'Hoist',
                notes: 'Hoist Role Managers can manage roles and their memberships.',
                roles: ['HOIST_ADMIN']
            ]
        ])

    }

    void clearCaches() {
        timer.forceRun()
        super.clearCaches()
    }

}
