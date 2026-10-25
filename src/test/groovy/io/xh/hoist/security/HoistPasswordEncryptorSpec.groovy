/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security

import spock.lang.Specification

/**
 * The jasypt hashes below were written by `BasicPasswordEncryptor` in hoist-core releases prior to
 * v44 and exist in app databases. They must keep verifying.
 */
class HoistPasswordEncryptorSpec extends Specification {

    // 'caf' + U+00E9 (precomposed é) vs 'cafe' + U+0301 (combining acute). Built from codepoints
    // because IDEs silently normalize source literals.
    private static final String CAFE_NFC = 'caf' + new String([0x00E9] as int[], 0, 1)
    private static final String CAFE_NFD = 'caf' + new String([0x0065, 0x0301] as int[], 0, 2)

    def encoder = new HoistPasswordEncryptor()

    def 'encryptPassword + checkPassword round trip works for arbitrary passwords'() {
        when:
        def encrypted = encoder.encryptPassword(plain)

        then:
        encrypted
        encrypted != plain
        encrypted.startsWith('$2') // BCrypt prefix
        encoder.checkPassword(plain, encrypted)

        and: 'wrong password fails'
        !encoder.checkPassword('wrong-' + plain, encrypted)
        !encoder.checkPassword('', encrypted)
        !encoder.checkPassword(null, encrypted)

        where:
        plain << ['password', 'a', 'café é résumé', '🚀 emoji 🎉',
                  'a-much-longer-passphrase-with-various-characters-!@#$%^&*()']
    }

    def 'encryptPassword salts, so repeated calls differ'() {
        when:
        def a = encoder.encryptPassword('secret')
        def b = encoder.encryptPassword('secret')

        then:
        a != b
        encoder.checkPassword('secret', a)
        encoder.checkPassword('secret', b)
    }

    def 'encryptPassword returns null for null/empty input'() {
        expect:
        encoder.encryptPassword(null) == null
        encoder.encryptPassword('') == null
    }

    def 'checkPassword verifies jasypt BasicPasswordEncryptor hashes'() {
        expect:
        encoder.checkPassword(plain, jasypt)

        where:
        plain                | jasypt
        'secret'             | 'Dn9nYr0xRJwMqmobJYydHNEVGYXDuuRv'
        'password'           | 'FJ4Cw1xTfDMVAHFv1bmv7OZmSsKnieZD'
        'X' * 256            | 'sdvN0Lh8lNANx56RJlYycFBxvbieeoU+'
        CAFE_NFC             | '5skexPacgCES39VwmXE3aH7vIzKBWb7s'
        'a b c\n!@#$%^&*()' | 'KUJ3pGX2vEbcCgE5Wck4b9m7mFV8e89n'
    }

    def 'jasypt hashes verify against the NFD form of the plaintext, as jasypt normalized to NFC'() {
        expect:
        encoder.checkPassword(CAFE_NFD, '5skexPacgCES39VwmXE3aH7vIzKBWb7s')
    }

    def 'checkPassword rejects wrong or malformed input'() {
        expect:
        !encoder.checkPassword('wrong', 'Dn9nYr0xRJwMqmobJYydHNEVGYXDuuRv')
        !encoder.checkPassword('SECRET', 'Dn9nYr0xRJwMqmobJYydHNEVGYXDuuRv')
        !encoder.checkPassword('any', 'not-a-valid-hash-at-all')
        !encoder.checkPassword('any', '')
        !encoder.checkPassword('any', null)
        !encoder.checkPassword('any', Base64.encoder.encodeToString(new byte[16]))
    }
}
