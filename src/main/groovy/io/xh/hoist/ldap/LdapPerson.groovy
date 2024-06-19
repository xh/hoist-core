package io.xh.hoist.ldap

import javax.management.Attribute

/**
 * Minimal set of attributes returned by a search for objectCategory=Person in Microsoft's AD LDAP implementation.
 * If you need more attributes, you can extend this class and add them.
 *
 * Available attributes can be found here:
 * https://learn.microsoft.com/en-us/archive/technet-wiki/12037.active-directory-get-aduser-default-and-extended-properties
 */
class LdapPerson extends LdapObject {

    String name
    String displayname
    String givenname
    String sn
    String mail

    static LdapPerson create(Collection<Attribute> atts) {
        def ret = new LdapPerson()
        ret.populate(atts)
        ret
    }

    static List<String> getKeys() {
        LdapObject.keys + ['name', 'displayname', 'givenname', 'sn', 'mail']
    }
}
