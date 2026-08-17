/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.role.provided

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.cachedvalue.CachedValue
import io.xh.hoist.config.ConfigService
import io.xh.hoist.config.ConfigSpec
import io.xh.hoist.directory.DirectoryService
import io.xh.hoist.entra.EntraIdService
import io.xh.hoist.ldap.LdapService
import io.xh.hoist.role.BaseRoleService
import io.xh.hoist.user.HoistUser
import io.xh.hoist.util.ErrorOr
import io.xh.hoist.util.Timer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static io.xh.hoist.util.Utils.isLocalDevelopment
import static io.xh.hoist.util.Utils.isProduction
import static java.util.Collections.*

/**
 * Optional concrete implementation of {@link BaseRoleService} for applications that use Hoist's
 * built-in, database-backed role management and its associated Admin Console UI.
 *
 * <p>Applications that opt in to this default implementation must either:
 * <ol>
 *   <li>Define a `RoleService` class that extends this class. The app can then override its
 *       protected methods to customize behavior, including
 *       {@link #doLoadUsersForDirectoryGroups} to resolve external directory group memberships
 *       (see below).</li>
 *   <li>Register this class directly as `roleService` via
 *       `grails-app/conf/spring/resources.groovy`, if no customizations are required.</li>
 * </ol>
 *
 * <p>This service provides:
 * <ul>
 *   <li>A {@link Role} domain class to persist roles and their memberships to the app's primary
 *       database. Role members can include directly-assigned users, other roles (for role
 *       inheritance), and external directory groups (see below).</li>
 *   <li>A fully-featured management UI within the hoist-react Admin Console, where admins with
 *       the HOIST_ROLE_MANAGER role can create, view, and manage roles and their
 *       memberships.</li>
 *   <li>Preloaded and cached role assignments, including any resolved directory group
 *       memberships, for efficient querying with a configurable refresh interval.</li>
 * </ul>
 *
 * <p><b>Directory groups.</b> In addition to (or instead of) assigning users directly as
 * members, admins can assign "directory groups" to a role - pointers to groups maintained within
 * a corporate directory such as LDAP / Active Directory or Microsoft Entra ID. Users within
 * those groups then inherit membership in the role. Roles themselves are still created and
 * managed in the local app database via the Admin Console. The default implementation resolves
 * groups via an enabled {@link DirectoryService} implementation - {@link LdapService} or
 * {@link EntraIdService} - see {@link #getDirectoryService} for selection details. Override
 * {@link #doLoadUsersForDirectoryGroups} to resolve groups from different or additional external
 * sources. If that method throws, this service logs an error and continues to use the result of
 * the last successful lookup. A call to {@link #clearCaches} also clears that cached lookup.
 *
 * <p>This service and its Admin Console UI are configurable via a JSON `xhRoleModuleConfig`
 * soft-config, created by this service on startup if not found. Supported keys:
 * <ul>
 *   <li>`refreshIntervalSecs` - seconds between refreshes of the role membership cache. Changes
 *       made via the Admin Console trigger an immediate refresh, so this setting primarily
 *       controls how quickly changes made to external directory groups sync to effective role
 *       memberships.</li>
 *   <li>`directoryGroupProvider` - which directory service resolves directory groups: `ldap`,
 *       `entraId`, or the default `auto`. See {@link #getDirectoryService}.</li>
 * </ul>
 *
 * @see BaseRoleService for additional documentation on the core RoleService API and its usage.
 */
class DefaultRoleService extends BaseRoleService {

    static clearCachesConfigs = ['xhRoleModuleConfig']

    ConfigService configService
    LdapService ldapService
    EntraIdService entraIdService
    DefaultRoleUpdateService defaultRoleUpdateService

    private Timer timer
    protected CachedValue<Map<String, Set<String>>> _allRoleAssignments = createCachedValue(
        name: 'roleAssignments',
        replicate: true,
        onChange: {
            _roleAssignmentsByUser = new ConcurrentHashMap()
        }
    )

    // Derived lazy cache on each instance
    protected ConcurrentMap<String, Set<String>> _roleAssignmentsByUser = new ConcurrentHashMap<>()

    // Local state for primary when computing role assignment
    protected Map<String, Set<String>> _usersForDirectoryGroups = emptyMap()

    // Support granting key Hoist admin roles to an instance-configured user in local dev only,
    // for initial bootstrapping during development when databased roles not yet created.
    private String bootstrapAdminUser = null
    private final Set<String> bootstrapAdminRoles = ['HOIST_ADMIN', 'HOIST_ADMIN_READER', 'HOIST_ROLE_MANAGER']

    void init() {
        ensureRequiredConfigAndRolesCreated()

        if (isLocalDevelopment && !isProduction) {
            bootstrapAdminUser = getInstanceConfig('bootstrapAdminUser')?.toLowerCase()
            if (bootstrapAdminUser) {
                logInfo("$bootstrapAdminUser configured as local development bootstrapAdminUser - will be granted $bootstrapAdminRoles")
            }
        }


        timer = createTimer(
            name: 'refreshRoles',
            runFn: this.&refreshRoleAssignments,
            interval: { config.refreshIntervalSecs as int * SECONDS },
            runImmediatelyAndBlock: true,
            primaryOnly: true
        )
        _allRoleAssignments.ensureAvailable()
    }

    //---------------------------------------
    // Implementation of Base Role Service
    //------------------------------------
    @Override
    Map<String, Set<String>> getAllRoleAssignments() {
        _allRoleAssignments.get()
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

        if (bootstrapAdminUser == username) {
            ret += bootstrapAdminRoles
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
     * True if this implementation supports the direct assignment of users to roles (the
     * default). Override to return false to prohibit, here and within the Admin Console UI.
     */
    boolean getUserAssignmentSupported() {
        return true
    }

    /**
     * True if this implementation supports the assignment of users to roles via directory
     * groups (the default). Override to return false to prohibit, here and within the Admin
     * Console UI.
     */
    boolean getDirectoryGroupsSupported() {
        return true
    }

    /**
     * Short description of the expected form of a directory group identifier, displayed as a
     * hint (e.g. tooltip) within the Admin Console UI. Delegates to the selected
     * {@link DirectoryService} - see {@link #getDirectoryService}.
     */
    String getDirectoryGroupsDescription() {
        directoryService.directoryGroupsDescription
    }

    /**
     * Resolve directory group identifiers to their member users.
     *
     * <p>The default implementation delegates to the selected {@link DirectoryService}
     * implementation - see {@link #getDirectoryService}. Override this method to resolve groups
     * from different, or additional, external sources.
     *
     * <p>If strictMode is true, implementations must throw on any partial failure. Otherwise
     * they log the failure and return whatever groups they can load.
     *
     * <p>If no directory service is enabled, the default implementation returns an error
     * description for every group rather than throwing, keeping role resolution and the Admin
     * Console UI functional (with inline warnings) in a misconfigured app.
     *
     * @return Map of directory group identifier to an {@link ErrorOr} holding either the Set of
     *         assigned usernames (on success) or a description of the lookup error (on failure).
     */
    protected Map<String, ErrorOr<Set<String>>> doLoadUsersForDirectoryGroups(Set<String> groups, boolean strictMode) {
        def svc = directoryService
        if (!groups) return emptyMap()
        if (!svc.enabled) return notEnabledResult(groups)
        svc.loadUsersForDirectoryGroups(groups, strictMode)
    }

    /**
     * The {@link DirectoryService} implementation used to resolve directory groups and back
     * related Admin Console UI features.
     *
     * <p>Selected via the optional `directoryGroupProvider` key in `xhRoleModuleConfig` -
     * `'ldap'`, `'entraId'`, or the default `'auto'`, which resolves to whichever single
     * implementation is enabled. When both are enabled under auto, this method logs an ERROR
     * and returns the LDAP implementation, so that an app experimenting with Entra ID does not
     * silently switch its role memberships to the new source.
     */
    protected DirectoryService getDirectoryService() {
        String provider = config.directoryGroupProvider ?: 'auto'
        switch (provider) {
            case 'ldap': return ldapService
            case 'entraId': return entraIdService
            case 'auto': break
            default:
                logError("Unknown xhRoleModuleConfig.directoryGroupProvider '$provider'", "expected 'ldap', 'entraId', or 'auto'", 'falling back to auto selection')
        }

        if (ldapService.enabled && entraIdService.enabled) {
            logError('Both LdapService and EntraIdService are enabled', "set xhRoleModuleConfig.directoryGroupProvider to select one", 'using LdapService')
            return ldapService
        }
        return entraIdService.enabled ? entraIdService : ldapService
    }

    /**
     * Resolve display information for directory groups already assigned to roles, for the
     * Admin Console UI. Returns a Map of group identifier to an {@link ErrorOr} holding either
     * a Map with a `displayName` key or a description of the lookup error.
     */
    Map<String, ErrorOr<Map>> describeDirectoryGroups(Set<String> groups) {
        def svc = directoryService
        if (!groups) return emptyMap()
        if (!svc.enabled) return notEnabledResult(groups)
        svc.describeDirectoryGroups(groups)
    }

    /**
     * Search the directory for groups by partial name, for the Admin Console UI. Returns a
     * List of Maps, each with `id` and `displayName` keys.
     */
    List<Map> searchDirectoryGroups(String namePart) {
        def svc = directoryService
        (namePart && svc.enabled) ? svc.searchDirectoryGroups(namePart) : []
    }

    /**
     * Ensure that the required soft-config entry for this service and a minimal set of required
     * Hoist roles have been created. Called by init() on app startup.
     *
     * <p>Override this method with an additional call to {@link #ensureRequiredRolesCreated} to
     * create any further roles required by the application on startup. Call this super
     * implementation as well, to create the roles required by Hoist.
     *
     * <p>(Overriding is preferable to a direct call to ensureRequiredRolesCreated within init(),
     * because this superclass rebuilds its cache of role assignments within its init method.)
     */
    protected void ensureRequiredConfigAndRolesCreated() {
        configService.ensureRequiredConfigsCreated([
            new ConfigSpec(
                name: 'xhRoleModuleConfig',
                valueType: 'json',
                defaultValue: [
                    refreshIntervalSecs: 300,
                    directoryGroupProvider: 'auto'
                ],
                groupName: 'xh.io',
                note: 'Configures built-in role management via DefaultRoleService.'
            )
        ])

        ensureRequiredRolesCreated([
            new RoleSpec(
                name: 'HOIST_ADMIN',
                category: 'Hoist',
                notes: 'Hoist Admins have full access to all Hoist Admin tools and functionality.'
            ),
            new RoleSpec(
                name: 'HOIST_ADMIN_READER',
                category: 'Hoist',
                notes: 'Hoist Admin Readers have read-only access to all Hoist Admin tools and functionality.',
                roles: ['HOIST_ADMIN']
            ),
            new RoleSpec(
                name: 'HOIST_IMPERSONATOR',
                category: 'Hoist',
                notes: 'Hoist Impersonators can impersonate other users.',
                roles: ['HOIST_ADMIN']
            ),
            new RoleSpec(
                name: 'HOIST_ROLE_MANAGER',
                category: 'Hoist',
                notes: 'Hoist Role Managers can manage roles and their memberships.'
            )
        ])
    }

    /**
     * Check a list of core roles required for Hoist or application operation, and create any
     * that are missing with the supplied default values.
     *
     * <p>Note that this method does not modify roles that already exist. It cannot update the
     * membership of an existing role. It can only create new roles.
     *
     * @param roleSpecs List of {@link RoleSpec} defining the required roles.
     */
    void ensureRequiredRolesCreated(List<RoleSpec> roleSpecs) {
        defaultRoleUpdateService.ensureRequiredRolesCreated(roleSpecs)
    }

    /**
     * Assign a role to a user. No-op if the user already has the given role.
     *
     * <p>Typically called within Bootstrap code to ensure that a dedicated admin user has a
     * specific role on startup. Call only <b>after</b> service initialization has completed
     * (e.g. after parallelInit in Bootstrap), because this method checks the role assignment
     * cache. Do <b>not</b> call from within an implementation of
     * ensureRequiredConfigAndRolesCreated(), because the cache is not yet available at that
     * point in the lifecycle.
     */
    void assignRole(HoistUser user, String roleName) {
        defaultRoleUpdateService.assignRole(user, roleName)
    }


    //---------------------------
    // Implementation/Framework
    //---------------------------
    /** Framework entry point for directory group resolution - apps override {@link #doLoadUsersForDirectoryGroups}. */
    final Map<String, ErrorOr<Set<String>>> loadUsersForDirectoryGroups(Set<String> directoryGroups, boolean strictMode) {
        doLoadUsersForDirectoryGroups(directoryGroups, strictMode)
    }

    /**
     * Per-group error results for when no enabled {@link DirectoryService} is available.
     * DirectoryService methods throw when called while not enabled - this service instead
     * degrades gracefully, reporting the problem as data that role resolution can log and the
     * Admin Console UI can render as inline warnings.
     */
    private <T> Map<String, ErrorOr<T>> notEnabledResult(Set<String> groups) {
        groups.collectEntries { [it, ErrorOr.error('No enabled directory service in this application')] }
    }

    void refreshRoleAssignments() {
        withDebug('Refreshing role caches') {
            _allRoleAssignments.set(generateRoleAssignments())
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
                Map<String, Set<String>> usersForDirectoryGroups = [:]
                loadUsersForDirectoryGroups(groups, strictMode).each { k, v ->
                    if (v.success) {
                        usersForDirectoryGroups[k] = v.value
                    } else {
                        logError("Error resolving users for directory group", k, v.error)
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
                    if (effectiveRole) {
                        rolesToVisit << effectiveRole
                        ret << effectiveRole
                    } else {
                        logWarn("Role ${role.name} references non-existent role $it", "skipping")
                    }
                }
            }
        }
        return ret
    }


    protected Map getConfig() {
        configService.getMap('xhRoleModuleConfig')
    }

    void clearCaches() {
        _usersForDirectoryGroups = emptyMap()
        timer.forceRun()
        super.clearCaches()
    }

    Map getAdminStats() {
        [
            roleAssignments        : allRoleAssignments?.size(),
            roleAssignmentsByUser  : _roleAssignmentsByUser?.size(),
            usersForDirectoryGroups: _usersForDirectoryGroups?.size()
        ]
    }

}
