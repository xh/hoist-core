package io.xh.hoist.role.provided

import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import io.xh.hoist.user.HoistUser

import static io.xh.hoist.role.provided.RoleMember.Type.*

/**
 * Service to support built-in, database-backed Role management and its associated Admin Console UI.
 *
 * Requires applications to have opted-in to Hoist's {@link DefaultRoleService} for role management.
 * See that class for additional documentation and details.
 *
 * This class is intended to be used by Hoist and its default role admin UI - apps should
 * rarely (if ever) need to interact with it directly.
 */
class DefaultRoleUpdateService extends BaseService {
    def roleService,
        trackService

    boolean getEnabled() {
        roleService instanceof DefaultRoleService
    }

    DefaultRoleService getDefaultRoleService() {
        roleService instanceof DefaultRoleService ? roleService as DefaultRoleService : null
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
    void delete(String name) {
        ensureEnabled()

        Role roleToDelete = Role.get(name)

        RoleMember
            .findAllByTypeAndName(ROLE, name)
            .each { it.role.removeFromMembers(it) }

        roleToDelete.delete(flush: true)

        trackService.track(msg: "Deleted role: '$name'", category: 'Audit')
        defaultRoleService.refreshRoleAssignments()
    }


    @Transactional
    void ensureRequiredRolesCreated(List<Map> roleSpecs) {
        List<Role> currRoles = Role.list()
        int created = 0

        roleSpecs.each { spec ->
            Role currRole = currRoles.find { it.name == spec.name }
            if (!currRole) {
                Role newRole = new Role(
                    name: spec.name,
                    category: spec.category,
                    notes: spec.notes,
                    lastUpdatedBy: 'defaultRoleService'
                ).save()

                spec.users?.each {
                    newRole.addToMembers(type: USER, name: it, createdBy: 'defaultRoleService')
                }

                spec.directoryGroups?.each {
                    newRole.addToMembers(type: DIRECTORY_GROUP, name: it, createdBy: 'defaultRoleService')
                }

                spec.roles?.each {
                    newRole.addToMembers(type: ROLE, name: it, createdBy: 'defaultRoleService')
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

    @Transactional
    void assignRole(HoistUser user, String roleName) {
        if (!user.hasRole(roleName)) {
            def role = Role.get(roleName)
            if (role) {
                role.addToMembers(type: USER, name: user.username, createdBy: 'defaultRoleUpdateService')
                role.save(flush: true)
                defaultRoleService.refreshRoleAssignments()
            } else {
                logWarn("Failed to find role $roleName to assign to $user", "role will not be assigned")
            }
        }
    }

    @Transactional
    List<Role> bulkCategoryUpdate(List<String> roles, String category) {
        ensureEnabled()
        List<Role> updatedRoles = new ArrayList<Role>()
        roles.each { roleName ->
            Role role = Role.get(roleName)
            role.category = category
            role.save(flush: true)
            updatedRoles.push(role)
        }
        trackService.track(
            msg: "Updated ${roles.size()} roles.",
            category: 'Audit',
            data: [
                roles   : roles,
                category: category
            ]
        )
        return updatedRoles
    }

    //------------------------
    // Implementation
    //------------------------
    @Transactional
    private Role createOrUpdate(Map<String, Object> roleSpec, boolean isUpdate) {
        def users = roleSpec.remove('users'),
            directoryGroups = roleSpec.remove('directoryGroups'),
            roles = roleSpec.remove('roles')

        roleSpec.lastUpdatedBy = authUsername

        Role role
        if (isUpdate) {
            role = Role.get(roleSpec.name as String)
            roleSpec.each { k, v -> role[k] = v }
        } else {
            role = new Role(roleSpec).save(flush: true)
        }

        def userChanges = updateMembers(role, USER, users),
            directoryGroupChanges = updateMembers(role, DIRECTORY_GROUP, directoryGroups),
            roleChanges = updateMembers(role, ROLE, roles)

        role.save(flush: true)

        if (isUpdate) {
            trackService.track([

            ])
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

        defaultRoleService.refreshRoleAssignments()
        return role
    }

    private RoleMemberChanges updateMembers(Role owner, RoleMember.Type type, List<String> members) {
        RoleMemberChanges changes = new RoleMemberChanges()

        List<RoleMember> existingMembers = RoleMember
            .list()
            .findAll { it.role == owner && it.type == type }

        if (type == USER) {
            members = members*.toLowerCase()
        }

        existingMembers.each { member ->
            if (!members.contains(member.name)) {
                owner.removeFromMembers(member)
                changes.removed << member.name
            }
        }

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

        return changes
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new RuntimeException("Action not enabled - the Hoist-provided DefaultRoleService must be used to enable role management via this service")
        }
    }

    private class RoleMemberChanges {
        List<String> added = []
        List<String> removed = []
    }
}
