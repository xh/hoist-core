package io.xh.hoist.role.provided

import io.xh.hoist.exception.RoutineRuntimeException
import io.xh.hoist.json.JSONFormat

import static io.xh.hoist.role.provided.RoleMember.Type.*

/**
 * Backing domain class for Hoist's built-in role management.
 * Methods on this class are used internally by Hoist and should not be called directly by app code.
 *
 * @see DefaultRoleService - the optional RoleService implementation that uses this class.
 */
class Role implements JSONFormat {
    String name
    String category
    String notes
    Date lastUpdated
    String lastUpdatedBy

    static hasMany = [members: RoleMember]

    static mapping = {
        table 'xh_role'
        id name: 'name', generator: 'assigned', type: 'string'
        members cascade: 'all-delete-orphan', fetch: 'join', cache: true
        cache true
    }

    static constraints = {
        category nullable: true, maxSize: 30, blank: false
        notes nullable: true, maxSize: 1200
        lastUpdatedBy maxSize: 50
    }

    static beforeInsert = {
        if (Role.findByNameIlike(name)) {
            throw new RoutineRuntimeException('Role Name must be case-insensitive unique.')
        }
    }

    List<String> getUsers() {
        members.findAll { it.type == USER }.collect { it.name.toLowerCase() }
    }

    List<String> getDirectoryGroups() {
        members.findAll { it.type == DIRECTORY_GROUP }.collect { it.name }
    }

    List<String> getRoles() {
        members.findAll { it.type == ROLE }.collect { it.name }
    }

    Map formatForJSON() {[
        name: name,
        category: category,
        notes: notes,
        lastUpdated: lastUpdated,
        lastUpdatedBy: lastUpdatedBy
    ] }

}
