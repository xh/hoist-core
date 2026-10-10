/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.ldap

import org.apache.directory.api.ldap.model.entry.DefaultAttribute
import spock.lang.Specification

class LdapGroupSpec extends Specification {

    def 'member attribute is collected as a list'() {
        when:
        def group = LdapGroup.create([
            new DefaultAttribute('cn', 'admins'),
            new DefaultAttribute('member', 'cn=a', 'cn=b')
        ])

        then:
        group.cn == 'admins'
        group.member == ['cn=a', 'cn=b']
    }

    def 'missing member attribute is null rather than empty'() {
        expect:
        LdapGroup.create([new DefaultAttribute('cn', 'admins')]).member == null
    }

    def 'LdapConfig servers convert to LdapServerOptions with defaults'() {
        when:
        def cfg = new LdapConfig([servers: [[host: 'h1'], [host: 'h2', baseUserDn: 'ou=u']]])

        then:
        cfg.servers.size() == 2
        cfg.servers.every { it instanceof LdapConfig.LdapServerOptions }
        cfg.servers[0].host == 'h1'
        cfg.servers[0].baseUserDn == ''
        cfg.servers[1].baseUserDn == 'ou=u'
    }
}
