package io.xh.hoist.ldap

import javax.management.Attribute

class LdapPerson extends LdapObject {

    String givenname
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
