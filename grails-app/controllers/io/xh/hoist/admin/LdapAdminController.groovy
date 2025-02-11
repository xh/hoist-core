package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.ldap.LdapService
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class LdapAdminController extends BaseController {

    LdapService ldapService

    def findUsers(String sNamePart) {
        renderJSON(ldapService.findUsers(sNamePart))
    }

    def lookupUser(String dn) {
        renderJSON(ldapService.lookupUser(dn))
    }

    def lookupUserByName(String sName) {
        renderJSON(ldapService.lookupUserByName(sName))
    }

    def findGroups(String sNamePart) {
        renderJSON(ldapService.findGroups(sNamePart))
    }

    def lookupGroup(String dn) {
        renderJSON(ldapService.lookupGroup(dn))
    }

    def lookupGroupByName(String sName) {
        renderJSON(ldapService.lookupGroupByName(sName))
    }

    def lookupGroupMembers(String dn) {
        renderJSON(ldapService.lookupGroupMembers(dn))
    }

}
