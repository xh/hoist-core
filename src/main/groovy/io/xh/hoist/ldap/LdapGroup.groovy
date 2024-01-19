package io.xh.hoist.ldap

import javax.management.Attribute

class LdapGroup extends LdapObject {

    String cn
    List<String> member

    static LdapGroup create(Collection<Attribute> atts) {
        def ret = new LdapGroup()
        ret.populate(atts)
        ret
    }

    static List<String> getKeys() {
        LdapObject.keys + ['cn', 'member']
    }
}
