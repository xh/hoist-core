/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.ldap

import org.apache.directory.api.ldap.model.entry.Attribute

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
