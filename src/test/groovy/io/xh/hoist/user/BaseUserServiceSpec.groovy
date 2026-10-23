/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.user

import io.xh.hoist.test.fakes.TestUser
import spock.lang.Specification
import spock.lang.Subject

class BaseUserServiceSpec extends Specification {

    static class StubUserService extends BaseUserService {
        List<TestUser> users = []
        List<Boolean> listCalls = []

        HoistUser find(String username) { users.find { it.username == username } }

        List<? extends HoistUser> list(boolean activeOnly) {
            listCalls << activeOnly
            users
        }
    }

    @Subject
    StubUserService service = new StubUserService(users: [
        new TestUser('admin', ['HOIST_ADMIN', 'HOIST_IMPERSONATOR']),
        new TestUser('imp', ['HOIST_IMPERSONATOR']),
        new TestUser('imp2', ['HOIST_IMPERSONATOR']),
        new TestUser('plain')
    ])

    def 'user without the impersonator role has no targets and list is not called'() {
        expect:
        service.impersonationTargetsForUser(new TestUser('plain')) == []
        service.listCalls.isEmpty()
    }

    def 'non-admin impersonator can target everyone except admins'() {
        when:
        def targets = service.impersonationTargetsForUser(service.find('imp'))

        then:
        targets*.username == ['imp', 'imp2', 'plain']
    }

    def 'admin impersonator can target all active users'() {
        when:
        def targets = service.impersonationTargetsForUser(service.find('admin'))

        then:
        targets*.username == ['admin', 'imp', 'imp2', 'plain']
    }

    def 'only active users are requested'() {
        when:
        service.impersonationTargetsForUser(service.find('imp'))

        then:
        service.listCalls == [true]
    }
}
