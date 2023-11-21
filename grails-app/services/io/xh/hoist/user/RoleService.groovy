package io.xh.toolbox.user

import io.xh.hoist.role.Role
import io.xh.hoist.user.BaseRoleService
import grails.gorm.transactions.Transactional

import static io.xh.hoist.util.Utils.configService

/**
 * Every user in Toolbox is granted the base APP_READER role by default - this ensures that any
 * newly created users logging in via OAuth can immediately access the app.
 *
 * Other roles (HOIST_ADMIN) are sourced from a soft-configuration map of role -> username[].
 */
@Transactional
class RoleService extends BaseRoleService {

    static String ADMIN_ROLE = 'HOIST_ADMIN'
    static String ADMIN_READER_ROLE = 'HOIST_ADMIN_READER'
    static String READER_ROLE = 'APP_READER'
    static String IMPERSONATOR_ROLE = 'HOIST_IMPERSONATOR'

    void init() {
        cleanDatabaseAndInitFromConfig()
    }

    /**
     * This is used as a "pre-"migration script, responsible for loading the role configs stored
     * (now) temporarily as JSON config objects into the database, using the GORM `Role` domain
     * object. This function clears all roles from the database, reads from the config (adding
     * the associated roles) and then adds a few more roles for demoing and testing functionality.
     *
     * In all, this should leave 6 role objects in the database, with the following inheritance
     * chain:
     *
     *              super-duper
     *                  |
     *          ----- super -----
     *         /        |        \
     *        /  /--- admin ---\  \
     *      reader           impersonator
     *                            |
     *                        child role
     */
    private cleanDatabaseAndInitFromConfig() {
        println("STARTING INIT")
        Role.list().each {
            println("DELETING ROLE: " + it.name)
            it.inherits.clear()
            it.save(flush: true)
        }

        Role.list().each {
            it.delete(flush: true)
        }
        println("REMAINING ROLES: " + Role.list().collect{it.name})

        def confRoles = configService.getMap('roles')
        confRoles.each { role, users ->
            def r = new Role(
                name: role,
                groupName: 'xh_config',
                users: users.toSet(),
                directoryGroups: [],
            )
            r.lastUpdatedBy = "xh_role_service__init-script"
            r.save(flush: true)
        }

        // All users are granted a READER_ROLE as per class doc comment.
        def appReader = Role.findOrCreateWhere(
            name: READER_ROLE,
            groupName: 'xh_config',
            lastUpdatedBy: "xh_role_service__init-script"
        )
        appReader.users = allUsersTemporary
        appReader.save(flush: true)

        def adminReader = Role.findOrCreateWhere(
            name: ADMIN_READER_ROLE,
            groupName: 'xh_config',
            lastUpdatedBy: "xh_role_service__init-script"
        )

        // support hoist impersonation, by making it inherited by HOIST_ADMINS
        def hoistAdmin = Role.findByName(ADMIN_ROLE)
        def hoistImpersonator = new Role(
            name: IMPERSONATOR_ROLE,
            groupName: 'xh_config',
            lastUpdatedBy: "xh_role_service__init-script",
        ).save(flush: true)
        hoistAdmin.addToInherits(hoistImpersonator)
        hoistAdmin.addToInherits(appReader)
        hoistAdmin.addToInherits(adminReader)
        hoistAdmin.save(failOnError: true, flush: true)

        // create test roles with more complex inheritance
        def superRole = new Role(
            name: 'SUPER ROLE',
            groupName: 'tests',
            // this testing role can inherit all existing roles..
            notes: "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
            children: Role.list(),
            users: ["test_user_for_super_role@test.com"],
            lastUpdatedBy: "xh_role_service__init-script",
        ).save(flush: true)
        def superDuperRole = new Role(
            name: 'SUPER-DUPER ROLE',
            groupName: 'tests',
            // this testing role can inherit all existing roles..
            notes: "Lorem ipsum dolor sit amet",
            children: superRole,
            users: ["test_user_for_super-duper_role@test.com"],
            lastUpdatedBy: "xh_role_service__init-script",
        ).save(flush: true)
        def childRole = new Role(
            name: 'CHILD ROLE',
            groupName: 'tests',
            // this testing role can inherit all existing roles..
            notes: "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Phasellus sagittis nibh vel scelerisque viverra. Fusce turpis libero, auctor at orci et, rutrum volutpat urna. Sed ut urna urna. Vivamus ac sem dui. Pellentesque in est erat. Nulla porttitor quam ligula, quis fermentum orci tincidunt sit amet. Ut sed iaculis nunc, sit amet condimentum metus.\n" +
                "Proin viverra lobortis luctus. In lectus neque, porttitor eu dictum a, gravida venenatis diam. Nunc accumsan tortor vel magna sodales hendrerit. Pellentesque id sem a leo consectetur aliquam. Aenean rutrum ac purus sit amet auctor. Nam et felis eleifend, ultricies nulla sit amet, tempus nunc. Curabitur porta a leo quis eleifend. Fusce lobortis facilisis hendrerit. Aliquam porta blandit vulputate. Donec hendrerit molestie suscipit. Vestibulum sagittis nisl ante, in ultricies urna venenatis sed. Etiam hendrerit porttitor malesuada. Class aptent taciti sociosqu ad litora torquent per conubia nostra, per inceptos himenaeos. Proin dapibus ultrices lorem.",
            users: ["test_user_for_child_role@test.com"],
            lastUpdatedBy: "xh_role_service__init-script",
        ).save(flush: true)
        hoistImpersonator.addToInherits(childRole)
        hoistImpersonator.save(flush: true)

        println("CREATED ROLES: " + Role.list().collect{it.name})
    }

    private List<String> getAllUsersTemporary() {
        def confRoles = configService.getMap('roles')
        confRoles.collectMany {it.value }
    }
}
