/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.ldap

import org.apache.directory.api.ldap.model.entry.DefaultAttribute
import spock.lang.Specification

class LdapPersonSpec extends Specification {

    def 'create populates scalar attributes, normalizing mixed-case ids'() {
        when:
        def person = LdapPerson.create([
            new DefaultAttribute('cn', 'Bob'),
            new DefaultAttribute('sAMAccountName', 'bob'),
            new DefaultAttribute('givenName', 'Bob'),
            new DefaultAttribute('sn', 'Smith')
        ])

        then:
        person.cn == 'Bob'
        person.samaccountname == 'bob'
        person.givenname == 'Bob'
        person.sn == 'Smith'
    }

    def 'subclass keys are used when populating'() {
        when:
        def person = LdapPerson.create([new DefaultAttribute('givenName', 'Bob')])

        then:
        person.givenname == 'Bob'
    }

    def 'memberOf is collected as a list of strings'() {
        when:
        def person = LdapPerson.create([new DefaultAttribute('memberOf', 'cn=a', 'cn=b', 'cn=c')])

        then:
        person.memberof == ['cn=a', 'cn=b', 'cn=c']
    }

    def 'missing attributes are null'() {
        when:
        def person = LdapPerson.create([new DefaultAttribute('cn', 'Bob')])

        then:
        person.mail == null
        person.memberof == null
        person.sn == null
    }

    def 'a single valued key given several values takes the first'() {
        when:
        def person = LdapPerson.create([new DefaultAttribute('mail', 'a@x.com', 'b@x.com')])

        then:
        person.mail == 'a@x.com'
    }

    def 'key lists extend the base keys'() {
        expect:
        LdapPerson.keys == LdapObject.keys + ['givenname', 'sn']
        LdapGroup.keys == LdapObject.keys + ['member']
        LdapObject.usernameKeys.every { it in LdapObject.keys }
    }

    def 'default LdapConfig username attribute is a valid username key'() {
        expect:
        new LdapConfig([:]).usernameAttribute in LdapObject.usernameKeys
    }
}
