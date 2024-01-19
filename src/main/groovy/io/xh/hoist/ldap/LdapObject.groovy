package io.xh.hoist.ldap

import org.apache.directory.api.ldap.model.entry.Attribute

class LdapObject {

    List<String> memberof
    String samaccountname
    String distinguishedname

    protected void populate(Collection<Attribute> attributes) {
        def atts = attributes.collectEntries { [it.id , it] }
        keys.each { k ->
            Attribute att = atts[k]
            this[k] = k in ['memberof', 'member'] ? att?.collect { it.string } : att?.string
        }
    }

    static List<String> getKeys() {
        [ 'memberof', 'samaccountname', 'distinguishedname']
    }
}
