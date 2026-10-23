/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.entra

import spock.lang.Specification

class EntraUserSpec extends Specification {

    private static String b64(List<Integer> bytes) {
        Base64.encoder.encodeToString(bytes.collect { it as byte } as byte[])
    }

    private static EntraUser withImmutableId(String id) {
        new EntraUser(onPremisesImmutableId: id)
    }

    def 'onPremisesObjectGuid decodes the mixed-endian AD GUID layout'() {
        given:
        def id = b64([0x33, 0x22, 0x11, 0x00, 0x55, 0x44, 0x77, 0x66, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])

        expect:
        withImmutableId(id).onPremisesObjectGuid == '00112233-4455-6677-8899-aabbccddeeff'
    }

    def 'onPremisesObjectGuid emits lowercase hex for high bytes'() {
        expect:
        withImmutableId(b64([0xFF] * 16)).onPremisesObjectGuid == 'ffffffff-ffff-ffff-ffff-ffffffffffff'
    }

    def 'onPremisesObjectGuid is null for #label'() {
        expect:
        withImmutableId(value).onPremisesObjectGuid == null

        where:
        label                | value
        'null'               | null
        'empty'              | ''
        'invalid base64'     | 'not-base64!!'
        '15 bytes'           | b64([1] * 15)
        '17 bytes'           | b64([1] * 17)
        'embedded whitespace'| b64([1] * 16).take(8) + ' ' + b64([1] * 16).drop(8)
    }

    def 'create copies known keys and ignores extras'() {
        when:
        def user = EntraUser.create([
            id: 'abc', mail: 'a@b.com', accountEnabled: true, '@odata.type': 'x', bogus: 1
        ])

        then:
        user.id == 'abc'
        user.mail == 'a@b.com'
        user.accountEnabled
        user.displayName == null
    }

    def 'formatForJSON emits exactly the declared keys and not the derived guid'() {
        when:
        def json = EntraUser.create([id: 'abc']).formatForJSON()

        then:
        json.keySet() == EntraUser.keys as Set
        !json.containsKey('onPremisesObjectGuid')
        json.id == 'abc'
    }

    def 'key lists are consistent subsets'() {
        expect:
        EntraUser.usernameKeys.every { it in EntraUser.queryKeys }
        EntraUser.queryKeys.every { it in EntraUser.keys }
        EntraUser.queryKeys.every { EntraUser.getDeclaredField(it).type == String }
        'accountEnabled' in EntraUser.keys
        'onPremisesSyncEnabled' in EntraUser.keys
        !('accountEnabled' in EntraUser.queryKeys)
        !('onPremisesSyncEnabled' in EntraUser.queryKeys)
    }

    def 'default EntraIdConfig username attribute is a valid username key'() {
        expect:
        new EntraIdConfig([:]).usernameAttribute in EntraUser.usernameKeys
    }
}
