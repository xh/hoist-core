package io.xh.hoist.ldap

import groovy.transform.CompileStatic
import org.apache.directory.api.ldap.model.entry.Attribute

@CompileStatic
class LdapGroup extends LdapObject {

    /** DNs of all group members (yes, the property name looks singular, but is the collection) */
    List<String> member

    static LdapGroup create(Collection<Attribute> attributes) {
        def ret = new LdapGroup()
        ret.populate(attributes)
        ret
    }

    static List<String> getKeys() {
        LdapObject.keys + ['member']
    }
}
