/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.ldap

import org.apache.directory.api.ldap.model.entry.Attribute

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
