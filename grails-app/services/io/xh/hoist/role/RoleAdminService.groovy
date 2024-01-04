package io.xh.hoist.role

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import io.xh.hoist.user.HoistUser
import static io.xh.hoist.role.RoleMember.Type.*

/**
 * Service to support admin management of Hoist's persistent, database-backed {@link Role} class.
 *
 * This class is intended to be used by its associated admin controller - apps should rarely (if
 * ever) need to interact with it directly. An exception is {@link #ensureRequiredRolesCreated},
 * which apps might wish to call in their Bootstrap code to ensure that expected roles have been
 * created in a given database environment.
 *
 * @see io.xh.hoist.role.BaseRoleService - this is the service contract apps must implement and is
 *     by default the primary user/consumer of the Roles created and updated by the service below.
 */
class RoleAdminService extends BaseService {
    def roleService,
        trackService

    /**
     * Return all Roles with all available membership information and metadata, for display in the
     * Hoist Admin Console. Includes fully resolved effective users, directory groups, and roles.
     */
    @ReadOnly
    Map index() {
        List<Role> roles = Role.list()
        Map usersForDirectoryGroups = null,
            errorsForDirectoryGroups = null

        if (roleService.config.assignDirectoryGroups) {
            Set<String> directoryGroups = roles
                .collectMany(new HashSet<String>()) { it.directoryGroups }
            if (directoryGroups) {
                Map usersOrErrorsForDirectoryGroups = roleService.getUsersForDirectoryGroups(directoryGroups)
                (usersForDirectoryGroups, errorsForDirectoryGroups) =
                    usersOrErrorsForDirectoryGroups.split { it.value instanceof Set }
            }
        }

        [
            roles: roles.collect {
                Map<RoleMember.Type, List<EffectiveMember>> effectiveMembers = it.resolveEffectiveMembers()
                [
                    name: it.name,
                    category: it.category,
                    notes: it.notes,
                    lastUpdated: it.lastUpdated,
                    lastUpdatedBy: it.lastUpdatedBy,
                    inheritedRoles: it.listInheritedRoles(),
                    effectiveUsers: collectEffectiveUsers(effectiveMembers, usersForDirectoryGroups),
                    effectiveDirectoryGroups: effectiveMembers[DIRECTORY_GROUP],
                    effectiveRoles: effectiveMembers[ROLE],
                    members: it.members,
                ]
            },
            errors: [
                directoryGroups: errorsForDirectoryGroups
            ]
        ]
    }

    Role create(Map roleSpec) {
        createOrUpdate(roleSpec, false)
    }

    Role update(Map roleSpec) {
        createOrUpdate(roleSpec, true)
    }

    @Transactional
    void delete(String id) {
        Role roleToDelete = Role.get(id)

        RoleMember
            .findAllByTypeAndName(ROLE, id)
            .each { it.role.removeFromMembers(it) }

        roleToDelete.delete(flush:true)

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
     * Ensure that a user is a member of a set of roles.
     *
     * Typically called by bootstrapping mechanisms to ensure that core roles are
     * accessible by a dedicated admin user on startup.
     */
    @Transactional
    void ensureUserHasRoles(HoistUser user, List<String> roleNames) {
        roleNames.each { roleName ->
            if (!user.hasRole(roleName)) {
                def role = Role.get(roleName)
                if (!role) throw new RuntimeException("Unable to find role $roleName")
                role.addToMembers(type: USER, name: user.username, createdBy: 'hoist-bootstrap')
                role.save(flush: true)
                roleService.clearCaches()
            }
        }
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
                    role: roleSpec.name,
                    category: roleSpec.category,
                    notes: roleSpec.notes,
                    addedUsers: userChanges.added,
                    removedUsers: userChanges.removed,
                    addedDirectoryGroups: directoryGroupChanges.added,
                    removedDirectoryGroups: directoryGroupChanges.removed,
                    addedRoles: roleChanges.added,
                    removedRoles: roleChanges.removed
                ]
            )
        } else {
            trackService.track(
                msg: "Created role: '${roleSpec.name}'",
                category: 'Audit',
                data: [
                    role: roleSpec.name,
                    category: roleSpec.category,
                    notes: roleSpec.notes,
                    users: userChanges.added,
                    directoryGroups: directoryGroupChanges.added,
                    roles: roleChanges.added
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
            if (!existingMembers.any {it.name == member}) {
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
        Map<String, EffectiveUser> ret = [:].withDefault { new EffectiveUser([name: it])}

        effectiveMembers.each { type, members ->
            if (type == ROLE) return

            members.each { member ->
                if (type == USER && roleService.config.assignUsers) {
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

    class RoleMemberChanges {
        List<String> added = []
        List<String> removed = []
    }
}
