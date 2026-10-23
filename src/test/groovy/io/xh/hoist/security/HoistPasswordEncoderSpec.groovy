/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security

import spock.lang.Specification

class HoistPasswordEncoderSpec extends Specification {

    def 'encode + matches round trip works for arbitrary passwords'() {
        when:
        def encoded = HoistPasswordEncoder.encode(plain)

        then:
        encoded
        encoded != plain
        encoded.startsWith('$2') // BCrypt prefix
        HoistPasswordEncoder.matches(plain, encoded)

        and: 'wrong password fails'
        !HoistPasswordEncoder.matches('wrong-' + plain, encoded)
        !HoistPasswordEncoder.matches('', encoded)
        !HoistPasswordEncoder.matches(null, encoded)

        where:
        plain << ['password', 'a', 'café é résumé', '🚀 emoji 🎉',
                  'a-much-longer-passphrase-with-various-characters-!@#$%^&*()']
    }

    def 'encode produces a different hash each call (BCrypt random salt)'() {
        when:
        def a = HoistPasswordEncoder.encode('secret')
        def b = HoistPasswordEncoder.encode('secret')

        then:
        a != b
        HoistPasswordEncoder.matches('secret', a)
        HoistPasswordEncoder.matches('secret', b)
    }

    def 'encode returns null for null/empty input'() {
        expect:
        HoistPasswordEncoder.encode(null) == null
        HoistPasswordEncoder.encode('') == null
    }

    def 'matches transparently verifies legacy jasypt hashes'() {
        // Fixtures captured from jasypt 1.9.3's BasicPasswordEncryptor.encryptPassword(plain)
        expect:
        HoistPasswordEncoder.matches(plain, legacyEncoded)

        and: 'and exposes them as needing migration'
        HoistPasswordEncoder.isLegacyHash(legacyEncoded)

        where:
        plain             | legacyEncoded
        'secret'          | 'Dn9nYr0xRJwMqmobJYydHNEVGYXDuuRv'
        'password'        | 'FJ4Cw1xTfDMVAHFv1bmv7OZmSsKnieZD'
        'playwright-test' | '3hrIc7ebBL/NxnkkVk0OWUAMyxRVld3U'
    }

    def 'fresh BCrypt hashes are not flagged as legacy'() {
        given:
        def encoded = HoistPasswordEncoder.encode('whatever')

        expect:
        !HoistPasswordEncoder.isLegacyHash(encoded)
    }

    def 'malformed encoded strings are rejected gracefully'() {
        expect:
        !HoistPasswordEncoder.matches('any', 'not-a-valid-hash-at-all')
        !HoistPasswordEncoder.matches('any', '')
        !HoistPasswordEncoder.matches('any', null)
        !HoistPasswordEncoder.isLegacyHash(null)
        !HoistPasswordEncoder.isLegacyHash('')
    }
}
