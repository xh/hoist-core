/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security.crypto

import spock.lang.Specification

class AesTextCipherSpec extends Specification {

    private static final String PWD = 'dsd899s_*)jsk9dsl2fd223hpdj32))I@333'

    def 'round-trips arbitrary plaintext including unicode'() {
        given:
        def cipher = new AesTextCipher(PWD)

        expect:
        cipher.decrypt(cipher.encrypt(input)) == input

        where:
        input << ['', 'hello world', 'a-much-longer-value-that-should-still-encode-fine!',
                  'café é résumé', '🚀 emoji 🎉', 'ABCD' * 200]
    }

    def 'encrypt produces fresh output on every call (random IV/salt)'() {
        given:
        def cipher = new AesTextCipher(PWD)

        when:
        def a = cipher.encrypt('repeat')
        def b = cipher.encrypt('repeat')

        then:
        a != b
        cipher.decrypt(a) == 'repeat'
        cipher.decrypt(b) == 'repeat'
    }

    def 'isHoistFormat correctly tags new-format ciphertext'() {
        given:
        def cipher = new AesTextCipher(PWD)
        def newCt = cipher.encrypt('value')

        expect:
        AesTextCipher.isHoistFormat(newCt)
        newCt.startsWith(AesTextCipher.FORMAT_PREFIX)
        !AesTextCipher.isHoistFormat(null)
        !AesTextCipher.isHoistFormat('')
        !AesTextCipher.isHoistFormat('sYvsDA6GhTFy97SLuc8I3La99s6fkC8S') // legacy fixture
    }

    def 'decrypting non-hoist-format input throws'() {
        given:
        def cipher = new AesTextCipher(PWD)

        when:
        cipher.decrypt('not-a-hoist-aes-string')

        then:
        thrown(IllegalArgumentException)
    }

    def 'cross-instance decrypt works (same password)'() {
        given:
        def encrypting = new AesTextCipher(PWD)
        def decrypting = new AesTextCipher(PWD)

        expect:
        decrypting.decrypt(encrypting.encrypt('shared secret')) == 'shared secret'
    }

    def 'wrong password fails to decrypt'() {
        given:
        def encrypting = new AesTextCipher(PWD)
        def wrong = new AesTextCipher('something-different')
        def ct = encrypting.encrypt('value')

        when:
        wrong.decrypt(ct)

        then:
        // AES-GCM authentication failure surfaces as a javax.crypto.AEADBadTagException
        // (subclass of BadPaddingException) — captured here as any Throwable.
        thrown(Throwable)
    }

    def 'null inputs are rejected up front'() {
        given:
        def cipher = new AesTextCipher(PWD)

        when: cipher.encrypt(null)
        then: thrown(IllegalArgumentException)

        when: cipher.decrypt(null)
        then: thrown(IllegalArgumentException)
    }
}
