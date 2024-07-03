package io.xh.hoist.ldap

import groovy.transform.CompileStatic
import org.apache.directory.api.ldap.model.entry.Attribute

@CompileStatic
class LdapPerson extends LdapObject {

    /** First name of the person */
    String givenname

    /** Last (sur)name of the person */
    String sn

    static LdapPerson create(Collection<Attribute> atts) {
        def ret = new LdapPerson()
        ret.populate(atts)
        ret
    }

    static List<String> getKeys() {
        LdapObject.keys + ['givenname', 'sn']
    }
}
