package io.xh.hoist.ldap

import org.apache.directory.api.ldap.model.entry.Attribute

/**
 * Minimal set of attributes returned by a search for any `objectCategory` in Microsoft's AD LDAP implementation.
 * If you need more attributes, you can extend this class and add them in your subclass.
 * See LdapPerson and LdapGroup for examples.
 *
 * Available attributes can be found here:
 * https://learn.microsoft.com/en-us/archive/technet-wiki/12037.active-directory-get-aduser-default-and-extended-properties
 */
class LdapObject {

    String cn
    String displayname
    String distinguishedname
    String mail
    List<String> memberof
    String name
    String samaccountname

    protected void populate(Collection<Attribute> attributes) {
        def atts = attributes.collectEntries { [it.id , it] }
        keys.each { k ->
            Attribute att = atts[k]
            this[k] = k in ['memberof', 'member'] ? att?.collect { it.string } : att?.string
        }
    }

    static List<String> getKeys() {
        [ 'cn', 'displayname', 'distinguishedname', 'mail', 'memberof', 'name', 'samaccountname']
    }
}
