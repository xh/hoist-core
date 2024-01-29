package io.xh.hoist.role.provided

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import static io.xh.hoist.role.provided.RoleMember.Type.*

/**
 * Service to support built-in, database-backed Role management and its associated Admin Console UI.
 *
 * Requires applications to have opted-in to Hoist's {@link DefaultRoleService} for role management.
 * See that class for additional documentation and details.
 *
 * This class is intended to be used by its associated admin controller - apps should rarely (if
 * ever) need to interact with it directly.
 */
class DefaultRoleAdminService extends BaseService {
    def roleService,
        trackService

    boolean getEnabled() {
        roleService instanceof DefaultRoleService
    }

    DefaultRoleService getDefaultRoleService() {
        roleService instanceof DefaultRoleService ? roleService as DefaultRoleService : null
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

        if (defaultRoleService.directoryGroupsSupported) {
            Set<String> groups = roles.collectMany(new HashSet()) { it.directoryGroups }
            if (groups) {
                Map<String, Object> usersOrErrorsForGroups = defaultRoleService.loadUsersForDirectoryGroups(groups)
                usersForDirectoryGroups = usersOrErrorsForGroups.findAll { it.value instanceof Set }
                errorsForDirectoryGroups = usersOrErrorsForGroups.findAll { !(it.value instanceof Set) }
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

    Map getClientConfig() { [
        userAssignmentSupported: defaultRoleService.userAssignmentSupported,
        directoryGroupsSupported: defaultRoleService.directoryGroupsSupported,
        directoryGroupsDescription: defaultRoleService.directoryGroupsDescription
    ]}

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

        trackService.track(msg: "Deleted role: '$id'", category: 'Audit')
        defaultRoleService.clearCaches()
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

        defaultRoleService.clearCaches()
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

    private List<EffectiveUser> collectEffectiveUsers(
        Map<RoleMember.Type, List<EffectiveMember>> effectiveMembers,
        Map usersForDirectoryGroups
    ) {
        Map<String, EffectiveUser> ret = [:].withDefault { new EffectiveUser([name: it]) }

        effectiveMembers.each { type, members ->
            if (type == ROLE) return

            members.each { member ->
                if (type == USER && defaultRoleService.userAssignmentSupported) {
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
        if (!enabled) {
            throw new RuntimeException("Action not enabled - the Hoist-provided DefaultRoleService must be used to enable role management via this service")
        }
    }

    class RoleMemberChanges {
        List<String> added = []
        List<String> removed = []
    }
}
