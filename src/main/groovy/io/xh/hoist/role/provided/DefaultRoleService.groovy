/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.role.provided

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.role.BaseRoleService
import io.xh.hoist.user.HoistUser
import io.xh.hoist.util.DateTimeUtils
import io.xh.hoist.util.Timer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

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
 *     methods to customize behavior, including the option to implement
 *     `doLoadUsersForDirectoryGroups` to resolve external directory group memberships (see below).
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
 * maintained within a corporate Active Directory, or other external system.  The default
 * implementation will support specifying an LDAP group, if there is an  enabled LdapService in the
 * application.  Roles themselves must still be created + managed in the local app database via the
 * Admin Console, but in addition to (or instead of) assigning users directly as members, admins can
 * assign  directory groups to roles. Users within those groups will then inherit membership in the
 * Role.
 *
 * Applications wishing to extend this feature should override doLoadUsersForDirectoryGroups().
 * If doLoadUsersForDirectoryGroups() throws an exception, this service will use the last successful
 * lookup result and log an error. Clearing this service's caches will also clear the cached lookup.
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
        ldapService,
        defaultRoleUpdateService

    private Timer timer
    protected Map<String, Set<String>> _allRoleAssignments = emptyMap()
    protected ConcurrentMap<String, Set<String>> _roleAssignmentsByUser = new ConcurrentHashMap<>()
    protected Map<String, Object> _usersForDirectoryGroups = emptyMap()

    static clearCachesConfigs = ['xhRoleModuleConfig']

    void init() {
        ensureRequiredConfigAndRolesCreated()

        timer = createTimer(
            interval: { config.refreshIntervalSecs as int * DateTimeUtils.SECONDS },
            runFn: this.&refreshRoleAssignments,
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
        username = username.toLowerCase()
        Set<String> ret = _roleAssignmentsByUser[username]
        if (ret == null) {
            Set<String> userRoles = new HashSet()
            allRoleAssignments.each { role, users ->
                if (users.contains(username)) userRoles << role
            }
            ret = _roleAssignmentsByUser[username] = unmodifiableSet(userRoles) as Set<String>
        }
        if (
            getInstanceConfig('bootstrapAdminUser')?.toLowerCase() == username &&
                isLocalDevelopment &&
                !isProduction
        ) {
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
     * Defaults to true. Implementations that wish to prohibit this should return false.
     */
    boolean getDirectoryGroupsSupported() {
        return true
    }

    /**
     * Description of appropriate form of directory groups.
     * Short string for UI display (e.g. tooltip) in admin client.
     */
    String getDirectoryGroupsDescription() {
        'Specify the full LDAP Distinguished Name (DN) for the directory group to be included.'
    }

    /**
     *  Provide the users associated with directory group names.
     *
     * The default implementation will support specifying an LDAP group, and will require an
     * enabled LdapService in the application.  Override this method to customize directory-based
     * lookup to attach to different, or additional external datasources.
     *
     * If strictMode is true, implementations should throw on any partial failures.  Otherwise, they
     * should log, and make a best-faith effort to return whatever groups they can load.
     *
     * Method Map of directory group names to either:
     *  a) Set<String> of assigned users
     *     OR
     *  b) String describing lookup error.
     */
    protected Map<String, Object> doLoadUsersForDirectoryGroups(Set<String> groups, boolean strictMode) {
        if (!groups) return emptyMap()
        if (!ldapService.enabled) {
            return groups.collectEntries{[it, 'LdapService not enabled in this application']}
        }

        def foundGroups = new HashSet(),
            ret = [:]

        // 1) Determine valid groups
        ldapService
            .lookupGroups(groups, strictMode)
            .each {name, group ->
                if (group) {
                    foundGroups << name
                } else {
                    ret[name] = 'Directory Group not found'
                }
            }

        // 2) Search for members of valid groups
        ldapService
            .lookupGroupMembers(foundGroups, strictMode)
            .each {name, members ->
                ret[name] = members.collect(new HashSet()) { it.samaccountname.toLowerCase()}
            }

        return ret
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
                    refreshIntervalSecs  : 300
                ],
                groupName   : 'xh.io',
                note        : 'Configures built-in role management via DefaultRoleService.'
            ]
        ])

        ensureRequiredRolesCreated([
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
            ]
        ])
    }

    /**
     * Check a list of core roles required for Hoist/application operation - ensuring that these
     * roles are present. Will create missing roles with supplied default values if not found.
     *
     * May be called within an implementation of ensureRequiredConfigAndRolesCreated().
     *
     * @param requiredRoles - List of maps of [name, category, notes, users, directoryGroups, roles]
     */
    void ensureRequiredRolesCreated(List<Map> roleSpecs) {
        defaultRoleUpdateService.ensureRequiredRolesCreated(roleSpecs)
    }

    /**
     * Assign a role to a user.
     *
     * This method will be a no-op if the user already has the role provided.
     *
     * Typically called within Bootstrap code to ensure that a specific role is assigned to a
     * dedicated admin user on startup.
     *
     * May be called within an implementation of ensureRequiredConfigAndRolesCreated().
     */
    void assignRole(HoistUser user, String roleName) {
        defaultRoleUpdateService.assignRole(user, roleName)
    }


    //---------------------------
    // Implementation/Framework
    //---------------------------
    final Map<String, Object> loadUsersForDirectoryGroups(Set<String> directoryGroups, boolean strictMode) {
        doLoadUsersForDirectoryGroups(directoryGroups, strictMode)
    }

    void refreshRoleAssignments() {
        withDebug('Refreshing role caches') {
            _allRoleAssignments = unmodifiableMap(generateRoleAssignments())
            _roleAssignmentsByUser = new ConcurrentHashMap()
        }
    }

    @ReadOnly
    protected Map<String, Set<String>> generateRoleAssignments() {
        List<Role> roles = Role.list()

        if (directoryGroupsSupported) {
            Set<String> groups = roles.collectMany(new HashSet()) { it.directoryGroups }

            // Error handling on resolution.  Can be complex (e.g. parallel LDAP calls) so be robust.
            // If we don't have results, take any results we can get, but
            // if we do have results, never replace them with non-complete/imperfect set.
            boolean strictMode = _usersForDirectoryGroups as boolean
            try {
                Map<String, Object> usersForDirectoryGroups = [:]
                loadUsersForDirectoryGroups(groups, strictMode).each { k, v ->
                    if (v instanceof Set) {
                        usersForDirectoryGroups[k] = v
                    } else {
                        logError("Error resolving users for directory group", k, v)
                    }
                }
                _usersForDirectoryGroups = usersForDirectoryGroups
            } catch (Throwable e) {
                // Leave existing _usersForDirectoryGroups cache in place, log error, and continue.
                logError("Error resolving users for directory groups", e)
            }
        }

        roles.collectEntries { role ->
            Set<Role> effectiveRoles = getEffectiveRoles(role),
                        users = new HashSet(),
                        groups = new HashSet()

            effectiveRoles.each { effRole ->
                if (userAssignmentSupported) users.addAll(effRole.users)
                if (directoryGroupsSupported) groups.addAll(effRole.directoryGroups)
            }
            groups.each { group ->
                _usersForDirectoryGroups[group]?.each { users << it.toLowerCase() }
            }

            logTrace("Generated assignments for ${role.name}", "${users.size()} effective users")
            [role.name, users]
        }
    }

    // Get the other roles that effectively have a role, e.g.
    // users with the returned roles will also be granted the input role.
    protected Set<Role> getEffectiveRoles(Role role) {
        Set<Role> ret = [role]
        Set<String> visitedRoles = [role.name]
        Queue<Role> rolesToVisit = [role] as Queue

        while (role = rolesToVisit.poll()) {
            role.roles.each {
                if (!visitedRoles.contains(it)) {
                    visitedRoles << it
                    def effectiveRole = Role.get(it)
                    rolesToVisit << effectiveRole
                    ret << effectiveRole
                }
            }
        }
        return ret
    }


    protected Map getConfig() {
        configService.getMap('xhRoleModuleConfig')
    }

    void clearCaches() {
        _allRoleAssignments = emptyMap()
        _roleAssignmentsByUser = new ConcurrentHashMap()
        _usersForDirectoryGroups = emptyMap()
        timer.forceRun()
        super.clearCaches()
    }
}
