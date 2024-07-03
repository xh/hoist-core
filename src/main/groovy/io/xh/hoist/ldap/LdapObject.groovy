package io.xh.hoist.ldap

import groovy.transform.CompileStatic
import org.apache.directory.api.ldap.model.entry.Attribute

/**
 * Minimal set of attributes returned by a search for any `objectCategory` in Microsoft's AD LDAP
 * implementation. If you need more attributes, extend this class and add them in your subclass.
 * @see LdapPerson
 * @see LdapGroup
 *
 * Available attributes can be found here:
 * https://learn.microsoft.com/en-us/archive/technet-wiki/12037.active-directory-get-aduser-default-and-extended-properties
 */
@CompileStatic
class LdapObject {

    String cn
    String displayname
    String distinguishedname
    String mail
    List<String> memberof
    String name
    String samaccountname

    protected void populate(Collection<Attribute> attributes) {
        Map<String, Attribute> attsById = attributes.collectEntries { [it.id , it] }
        keys.each { k ->
            Attribute att = attsById[k]
            this[k] = k in ['memberof', 'member'] ? att?.collect { it.string } : att?.string
        }
    }

    static List<String> getKeys() {
        [ 'cn', 'displayname', 'distinguishedname', 'mail', 'memberof', 'name', 'samaccountname']
    }
}
