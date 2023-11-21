package io.xh.hoist.admin

import grails.gorm.transactions.Transactional
import io.xh.hoist.role.Role
import io.xh.hoist.security.Access
import io.xh.toolbox.user.RoleService

@Access(['HOIST_ADMIN_READER'])

class RolesAdminController extends AdminRestController {
    RoleService roleService
    static restTarget = Role
    static trackChanges = true

    protected void preprocessSubmit(Map submit) {
        submit.id = submit.name
        submit.lastUpdatedBy = authUsername
        submit.remove('undeletable')
    }

    @Transactional
    @Access(['HOIST_ADMIN'])
    delete() {
        Role roleToDelete = Role.get(params.id)
        if (roleToDelete.undeletable) {
            throw new RuntimeException("${params.id} cannot be deleted.")
        }
        Role.list().each { role ->
            role.inherits.remove(roleToDelete)
            role.save(flush: true)
        }
        super.delete()
    }

    /**
     * Get the impact of updating a role without actually committing the update
     */
    @Transactional(readOnly = true)
    updateImpact() {
        def data = parseRequestJSON().data
        preprocessSubmit(data)

        Role role = Role.get(data.id)
        List<String> inheritedRoles = role.listAllInheritedRoles().collect { it.role }
        Set<String> impactedUsers = role.listAllUsers().collect { it.name } as Set<String>,
            impactedDirectoryGroups = role.listAllDirectoryGroups().collect { it.name } as Set<String>

        bindData(role, data)
        List<String> newInheritedRoles = role.listAllInheritedRoles().collect { it.role }
        impactedUsers += role.listAllUsers().collect { it.name }
        impactedDirectoryGroups += role.listAllDirectoryGroups().collect { it.name }

        renderJSON(
            addedRoles: newInheritedRoles - inheritedRoles,
            removedRoles: inheritedRoles - newInheritedRoles,
            impactedUserCount: impactedUsers.size(),
            impactedDirectoryGroupCount: impactedDirectoryGroups.size()
        )
    }

    /**
     * Preview an updated role without actually committing the update
     */
    @Transactional(readOnly = true)
    auditionUpdate() {
        def data = parseRequestJSON().data
        preprocessSubmit(data)
        Role role = Role.get(data.id)
        bindData(role, data)
        renderJSON(success:true, data:role)
    }

    /**
     * Preview a new role without actually creating it
     */
    @Transactional(readOnly = true)
    auditionCreate() {
        def data = parseRequestJSON().data
        preprocessSubmit(data)
        Role role = Role.newInstance(data)
        renderJSON(success:true, data:role)
    }
}