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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

import static io.xh.hoist.role.provided.RoleMember.Type.DIRECTORY_GROUP
import static io.xh.hoist.role.provided.RoleMember.Type.USER
import static io.xh.hoist.util.Utils.isLocalDevelopment
import static io.xh.hoist.util.Utils.isProduction
import static java.util.Collections.emptySet
import static java.util.Collections.emptyMap
import static java.util.Collections.unmodifiableMap
import static java.util.Collections.unmodifiableSet
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig

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

 *  - Admins with the HOIST_ROLE_MANAGER role can create, view and manage roles and their
 *  memberships via a fully-featured Hoist React Admin Console UI (requires hoist-react >= v60).
 *
 *  - Roles and their memberships, including any resolved directory group memberships, are preloaded
 *    and cached by this service for efficient querying, with a configurable refresh interval.
 *
 * This service can assign role memberships based on "directory groups" - pointers to groups
 * maintained within a corporate Active Directory, LDAP, or equivalent system. Roles
 * themselves must still be created + managed in the local app database via the Admin Console,
 * but in addition to (or instead of) assigning users directly as members, admins can assign
 * directory groups to roles. Users within those groups will then inherit membership in the Role.
 *
 * Apps wishing to use this feature must extend this service and override.
 * getDirectoryGroupsSupported() and doLoadUsersForDirectoryGroups().
 *
 * Certain aspects of this service and its Admin Console UI are soft-configurable via a JSON
 * `xhRoleModuleConfig`. This service will create this config entry if not found on startup.
 *
 * The following config options are supported as keys in this map:
 *
 *  - refreshIntervalSecs: int - number of seconds between refreshes of the role membership cache.
 *    Changes made to roles via the Hoist Admin Console will trigger an immediate refresh of the
 *    cache. This setting primarily controls how quickly changes made to external directory groups
 *    will sync to effective role memberships.
 *
 *
 * @see BaseRoleService for additional documentation on the core RoleService API and its usage.
 */
class DefaultRoleService extends BaseRoleService {

    def configService,
        defaultRoleAdminService

    private Timer timer
    protected Map<String, Set<String>> _allRoleAssignments = emptyMap()
    protected ConcurrentMap<String, Set<String>> _roleAssignmentsByUser = new ConcurrentHashMap<>()

    static clearCachesConfigs = ['xhRoleModuleConfig']

    void init() {
        ensureRequiredConfigAndRolesCreated()

        timer = createTimer(
            interval: { config.refreshIntervalSecs as int * DateTimeUtils.SECONDS },
            runFn: this.&refreshCaches,
            runImmediatelyAndBlock: true
        )
    }

    //---------------------------------------
    // Implementation of Base Role Service
    //------------------------------------
    @Override
    Map<String, Set<String>> getAllRoleAssignments() {
        _allRoleAssignments
    }

    @Override
    Set<String> getRolesForUser(String username) {
        Set<String> ret = _roleAssignmentsByUser[username]
        if (ret == null) {
            Set<String> userRoles = new HashSet()
            allRoleAssignments.each { role, users ->
                if (users.contains(username)) userRoles << role
            }
            ret = _roleAssignmentsByUser[username] = unmodifiableSet(userRoles) as Set<String>
        }
        if (getInstanceConfig('bootstrapAdminUser') == username && isLocalDevelopment && !isProduction) {
            ret += ['HOIST_ADMIN', 'HOIST_ADMIN_READER', 'HOIST_ROLE_MANAGER']
        }
        ret
    }

    @Override
    Set<String> getUsersForRole(String role) {
        allRoleAssignments[role] ?: emptySet() as Set<String>
    }

    //---------------------------------
    // Main entry points for override
    //---------------------------------
    /**
     * Does this implementation support the specification of direct role assignment via usernames?
     * Defaults to true.  Implementations that wish to prohibit this should return false.
     * */
    boolean getUserAssignmentSupported() {
        return true
    }

    /**
     * Does this implementation support the specification of role assignment via directory?
     * Defaults to false.  Implementations supporting directory lookup should return true here
     * and implement the doLoadUsersForDirectoryGroups() method.
     */
    boolean getDirectoryGroupsSupported() {
        return false
    }

    /**
     * Description of appropriate form of directory groups, if supported.
     * Short string for UI display (e.g. tooltip) in admin client.
     */
    String getDirectoryGroupsDescription() {
        return null
    }

    /**
     * Override this method to enable directory-based lookup.  You must also override
     * getDirectoryGroupsSupported().
     *
     * Method Map of directory group names to either:
     *  a) Set<String> of assigned users
     *     OR
     *  b) String describing lookup error.
     */
    protected Map<String, Object> doLoadUsersForDirectoryGroups(Set<String> directoryGroups) {
        throw new UnsupportedOperationException('doLoadUsersForDirectoryGroups not implemented')
    }

    /**
     * Ensure that the required soft-config entry for this service has been created, along with a
     * minimal set of required Hoist roles. Called by init() on app startup.
     */
    protected void ensureRequiredConfigAndRolesCreated() {
        configService.ensureRequiredConfigsCreated([
            xhRoleModuleConfig: [
                valueType   : 'json',
                defaultValue: [
                    refreshIntervalSecs  : 30
                ],
                groupName   : 'xh.io',
                note        : 'Configures built-in role management via DefaultRoleService.'
            ]
        ])

        defaultRoleAdminService.ensureRequiredRolesCreated([
            [
                name    : 'HOIST_ADMIN',
                category: 'Hoist',
                notes   : 'Hoist Admins have full access to all Hoist Admin tools and functionality.'
            ],
            [
                name    : 'HOIST_ADMIN_READER',
                category: 'Hoist',
                notes   : 'Hoist Admin Readers have read-only access to all Hoist Admin tools and functionality.',
                roles   : ['HOIST_ADMIN']
            ],
            [
                name    : 'HOIST_IMPERSONATOR',
                category: 'Hoist',
                notes   : 'Hoist Impersonators can impersonate other users.',
                roles   : ['HOIST_ADMIN']
            ],
            [
                name    : 'HOIST_ROLE_MANAGER',
                category: 'Hoist',
                notes   : 'Hoist Role Managers can manage roles and their memberships.',
                roles   : ['HOIST_ADMIN']
            ]
        ])
    }


    //---------------------------
    // Implementation/Framework
    //---------------------------
    final Map<String, Object> loadUsersForDirectoryGroups(Set<String> directoryGroups) {
        // Wrapped here to avoid failing implementations from ever bringing down app.
        try {
            return doLoadUsersForDirectoryGroups(directoryGroups)
        } catch (Throwable e) {
            def errMsg = 'Error resolving Directory Groups'
            logError(errMsg, e)
            return directoryGroups.collectEntries {[it, errMsg] }
        }
    }

    protected void refreshCaches() {
        withDebug('Refreshing role caches') {
            _allRoleAssignments = unmodifiableMap(generateRoleAssignments())
            _roleAssignmentsByUser = new ConcurrentHashMap()
        }
    }

    @ReadOnly
    protected Map<String, Set<String>> generateRoleAssignments() {
        List<Role> roles = Role.list()
        Map<String, Object> usersForDirectoryGroups, errorsForDirectoryGroups

        if (directoryGroupsSupported) {
            Set<String> groups = roles.collectMany(new HashSet()) { it.directoryGroups }
            if (groups) {
                Map<String, Object> usersOrErrorsForGroups = loadUsersForDirectoryGroups(groups)
                usersForDirectoryGroups = usersOrErrorsForGroups.findAll { it.value instanceof Set }
                errorsForDirectoryGroups = usersOrErrorsForGroups.findAll { !(it.value instanceof Set) }

                errorsForDirectoryGroups.each { group, error ->
                    logError("Error resolving users for directory group", group, error)
                }
            }
        }

        roles.collectEntries { role ->
            Set<String> users = new HashSet<String>()
            Map<RoleMember.Type, List<EffectiveMember>> members = role.resolveEffectiveMembers()

            // The members[USER] set includes users directly assigned to this role, as well as any
            // users that inherit this role via direct assignments on other roles.
            if (userAssignmentSupported) {
                members[USER].each { users.add(it.name) }
            }

            // The members[DIRECTORY_GROUP] set includes groups directly assigned to this role, as
            // well as any groups that inherit this role via direct assignments on other roles.
            if (usersForDirectoryGroups) {
                members[DIRECTORY_GROUP].each {
                    def groupUsers = usersForDirectoryGroups[it.name]
                    if (groupUsers instanceof Set) users.addAll(groupUsers)
                }
            }

            logTrace("Generated assignments for ${role.name}", "${users.size()} effective users")
            return [role.name, users]
        }
    }

    protected Map getConfig() {
        configService.getMap('xhRoleModuleConfig')
    }

    void clearCaches() {
        timer.forceRun()
        super.clearCaches()
    }
}
