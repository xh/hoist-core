package io.xh.hoist.ldap

import javax.management.Attribute

class LdapPerson extends LdapObject{

    String name
    String email

    static LdapPerson create(Collection<Attribute> atts) {
        def ret = new LdapGroup()
        ret.populate(atts)
        ret
    }

    static List<String> getKeys() {
        LdapObject.keys + ['name', 'email']
    }
}
