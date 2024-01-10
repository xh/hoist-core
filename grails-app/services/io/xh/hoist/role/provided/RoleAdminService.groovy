package io.xh.hoist.role.provided

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import io.xh.hoist.role.BaseRoleService
import io.xh.hoist.role.provided.EffectiveMember
import io.xh.hoist.role.provided.EffectiveUser
import io.xh.hoist.role.provided.Role
import io.xh.hoist.role.provided.RoleMember
import io.xh.hoist.user.HoistUser
import static io.xh.hoist.role.provided.RoleMember.Type.*

/**
 * Service to support built-in, database-backed Role management and its associated Admin Console UI.
 *
 * Requires applications to have opted-in to Hoist's {@link DefaultRoleService} for role management.
 * See that class for additional documentation and details.
 *
 * This class is intended to be used by its associated admin controller - apps should rarely (if
 * ever) need to interact with it directly. An exception is {@link #ensureRequiredRolesCreated},
 * which apps might wish to call in their Bootstrap code to ensure that expected roles have been
 * created in a given database environment.
 */
class RoleAdminService extends BaseService {
    def roleService,
        trackService

    boolean getEnabled() {
        roleService instanceof DefaultRoleService
    }

    /**
     * List all Roles with all available membership information and metadata, for display in the
     * Hoist Admin Console. Includes fully resolved effective users, directory groups, and roles.
     */
    @ReadOnly
    List<Map> list() {
        ensureEnabled()

        List<Role> roles = Role.list()
        Map<String, Object> usersForDirectoryGroups = null,
                            errorsForDirectoryGroups = null

        if (config.assignDirectoryGroups) {
            Set<String> directoryGroups = roles
                .collectMany(new HashSet<String>()) { it.directoryGroups }
            if (directoryGroups) {
                Map<String, Object> usersOrErrorsForDirectoryGroups = roleService
                    .getUsersForDirectoryGroups(directoryGroups)
                usersForDirectoryGroups = usersOrErrorsForDirectoryGroups
                    .findAll { it.value instanceof Set }
                errorsForDirectoryGroups = usersOrErrorsForDirectoryGroups
                    .findAll { !(it.value instanceof Set) }
            }
        }

        roles.collect {
            Map<RoleMember.Type, List<EffectiveMember>> effectiveMembers = it
                .resolveEffectiveMembers()
            List<EffectiveMember> effectiveDirectoryGroups = effectiveMembers[DIRECTORY_GROUP]
                ?: []
            Set<String> effectiveDirectoryGroupNames = effectiveDirectoryGroups
                .collect { it.name }.toSet()

            [
                name                    : it.name,
                category                : it.category,
                notes                   : it.notes,
                lastUpdated             : it.lastUpdated,
                lastUpdatedBy           : it.lastUpdatedBy,
                inheritedRoles          : it.listInheritedRoles(),
                effectiveUsers          : collectEffectiveUsers(effectiveMembers, usersForDirectoryGroups),
                effectiveDirectoryGroups: effectiveDirectoryGroups,
                effectiveRoles          : effectiveMembers[ROLE],
                members                 : it.members,
                errors                  : [
                    directoryGroups: errorsForDirectoryGroups
                        .findAll { k, v -> k in effectiveDirectoryGroupNames }
                ]
            ]
        }
    }

    Role create(Map roleSpec) {
        ensureEnabled()
        createOrUpdate(roleSpec, false)
    }

    Role update(Map roleSpec) {
        ensureEnabled()
        createOrUpdate(roleSpec, true)
    }

    @Transactional
    void delete(String id) {
        ensureEnabled()

        Role roleToDelete = Role.get(id)

        RoleMember
            .findAllByTypeAndName(ROLE, id)
            .each { it.role.removeFromMembers(it) }

        roleToDelete.delete(flush: true)

        trackService.track(
            msg: "Deleted role: '$id'",
            category: 'Audit'
        )
        roleService.clearCaches()
    }


    /**
     * Check a list of core roles required for Hoist/application operation - ensuring that these
     * roles are present. Will create missing roles with supplied default values if not found.
     *
     * Called by Hoist Core Bootstrap.
     *
     * @param requiredRoles - List of maps of [name, category, notes, users, directoryGroups, roles]
     */
    @Transactional
    void ensureRequiredRolesCreated(List<Map> roleSpecs) {
        ensureEnabled()

        List<Role> currRoles = Role.list()
        int created = 0

        roleSpecs.each { spec ->
            Role currRole = currRoles.find { it.name == spec.name }
            if (!currRole) {
                Role createdRole = new Role(
                    name: spec.name,
                    category: spec.category,
                    notes: spec.notes,
                    lastUpdatedBy: 'hoist-bootstrap'
                ).save()

                spec.users?.each {
                    createdRole.addToMembers(
                        type: USER,
                        name: it,
                        createdBy: 'hoist-bootstrap'
                    )
                }

                spec.directoryGroups?.each {
                    createdRole.addToMembers(
                        type: DIRECTORY_GROUP,
                        name: it,
                        createdBy: 'hoist-bootstrap'
                    )
                }

                spec.roles?.each {
                    createdRole.addToMembers(
                        type: ROLE,
                        name: it,
                        createdBy: 'hoist-bootstrap'
                    )
                }

                logWarn(
                    "Required role ${spec.name} missing and created with default value",
                    'verify default is appropriate for this application'
                )
                created++
            }
        }

        logDebug("Validated presense of ${roleSpecs.size()} required roles", "created $created")
    }

    /**
     * Ensure that a user has been assigned a set of database-backed Roles.
     *
     * Typically called within Bootstrap code to ensure that core roles are assigned to a dedicated
     * admin user on startup.
     */
    @Transactional
    void ensureUserHasRoles(HoistUser user, List<String> roleNames) {
        ensureEnabled()

        def didChange = false
        roleNames.each { roleName ->
            if (!user.hasRole(roleName)) {
                def role = Role.get(roleName)
                if (role) {
                    role.addToMembers(type: USER, name: user.username, createdBy: 'hoist-bootstrap')
                    role.save(flush: true)
                    didChange = true
                } else {
                    logWarn("Failed to find role $roleName to assign to $user", "role will not be assigned")
                }
            }
        }

        if (didChange) roleService.clearCaches()
    }

    //------------------------
    // Implementation
    //------------------------

    @Transactional
    private Role createOrUpdate(Map<String, Object> roleSpec, boolean isUpdate) {
        List<String> users = roleSpec.users as List<String>,
                     directoryGroups = roleSpec.directoryGroups as List<String>,
                     roles = roleSpec.roles as List<String>
        roleSpec.remove('users')
        roleSpec.remove('directoryGroups')
        roleSpec.remove('roles')

        Role role
        if (isUpdate) {
            role = Role.get(roleSpec.name as String)
            roleSpec.each { k, v -> role[k] = v }
        } else {
            role = new Role(roleSpec)
        }

        def userChanges = updateMembers(role, USER, users),
            directoryGroupChanges = updateMembers(role, DIRECTORY_GROUP, directoryGroups),
            roleChanges = updateMembers(role, ROLE, roles)

        role.setLastUpdatedBy(authUsername)
        role.save(flush: true)

        if (isUpdate) {
            trackService.track(
                msg: "Edited role: '${roleSpec.name}'",
                category: 'Audit',
                data: [
                    role                  : roleSpec.name,
                    category              : roleSpec.category,
                    notes                 : roleSpec.notes,
                    addedUsers            : userChanges.added,
                    removedUsers          : userChanges.removed,
                    addedDirectoryGroups  : directoryGroupChanges.added,
                    removedDirectoryGroups: directoryGroupChanges.removed,
                    addedRoles            : roleChanges.added,
                    removedRoles          : roleChanges.removed
                ]
            )
        } else {
            trackService.track(
                msg: "Created role: '${roleSpec.name}'",
                category: 'Audit',
                data: [
                    role           : roleSpec.name,
                    category       : roleSpec.category,
                    notes          : roleSpec.notes,
                    users          : userChanges.added,
                    directoryGroups: directoryGroupChanges.added,
                    roles          : roleChanges.added
                ]
            )
        }

        roleService.clearCaches()
        return role
    }

    private RoleMemberChanges updateMembers(Role owner, RoleMember.Type type, List<String> members) {
        RoleMemberChanges changes = new RoleMemberChanges()

        List<RoleMember> existingMembers = RoleMember
            .list()
            .findAll { it.role == owner && it.type == type }

        members.each { member ->
            if (!existingMembers.any { it.name == member }) {
                owner.addToMembers(
                    type: type,
                    name: member,
                    createdBy: authUsername
                )
                changes.added << member
            }
        }

        existingMembers.each { member ->
            if (!members.contains(member.name)) {
                owner.removeFromMembers(member)
                changes.removed << member.name
            }
        }

        return changes
    }

    private List<EffectiveUser> collectEffectiveUsers(
        Map<RoleMember.Type, List<EffectiveMember>> effectiveMembers,
        Map usersForDirectoryGroups
    ) {
        Map<String, EffectiveUser> ret = [:].withDefault { new EffectiveUser([name: it]) }

        effectiveMembers.each { type, members ->
            if (type == ROLE) return

            members.each { member ->
                if (type == USER && config.assignUsers) {
                    member.sourceRoles.each { role ->
                        ret[member.name].addSource(role, null)
                    }
                } else if (type == DIRECTORY_GROUP && usersForDirectoryGroups) {
                    usersForDirectoryGroups[member.name]?.each { user ->
                        member.sourceRoles.each { role ->
                            ret[user].addSource(role, member.name)
                        }
                    }
                }
            }
        }

        ret.values() as List<EffectiveUser>
    }

    private void ensureEnabled() {
        if (!enabled) throw new RuntimeException("RoleAdminService not enabled - the Hoist-provided DefaultRoleService must be used to enable role management via this service")
    }

    private Map getConfig() {
        return roleService.config
    }

    class RoleMemberChanges {
        List<String> added = []
        List<String> removed = []
    }
}
