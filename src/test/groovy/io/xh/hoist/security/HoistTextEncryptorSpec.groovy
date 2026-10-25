/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security

import spock.lang.Specification

/**
 * The jasypt ciphertexts below were written by `BasicTextEncryptor` in hoist-core releases prior
 * to v44 and exist in app databases as `pwd` config values. They must keep decrypting.
 */
class HoistTextEncryptorSpec extends Specification {

    // Same key historically hard-coded in AppConfig.groovy for `pwd` value encryption.
    private static final String KEY = 'dsd899s_*)jsk9dsl2fd223hpdj32))I@333'

    // 'caf' + U+00E9 (precomposed é) vs 'cafe' + U+0301 (combining acute). Built from codepoints
    // because IDEs silently normalize source literals.
    private static final String CAFE_NFC = 'caf' + new String([0x00E9] as int[], 0, 1)
    private static final String CAFE_NFD = 'caf' + new String([0x0065, 0x0301] as int[], 0, 2)

    def encryptor = new HoistTextEncryptor(KEY)

    def 'round-trips arbitrary plaintext including unicode'() {
        when:
        def encrypted = encryptor.encrypt(plain)

        then:
        encrypted != plain
        encryptor.decrypt(encrypted) == plain

        where:
        plain << ['', 'hello world', 'a-much-longer-value-that-should-still-encode-fine!',
                  'café é résumé', '🚀 emoji 🎉', 'ABCD' * 200]
    }

    def 'encrypt produces fresh output on every call (random salt)'() {
        when:
        def a = encryptor.encrypt('repeat')
        def b = encryptor.encrypt('repeat')

        then:
        a != b
        encryptor.decrypt(a) == 'repeat'
        encryptor.decrypt(b) == 'repeat'
    }

    def 'a separate instance with the same key decrypts'() {
        expect:
        new HoistTextEncryptor(KEY).decrypt(encryptor.encrypt('shared secret')) == 'shared secret'
    }

    def 'wrong key fails to decrypt'() {
        when:
        new HoistTextEncryptor('something-different').decrypt(encryptor.encrypt('value'))

        then:
        // Either a padding error or garbage that fails UTF-8 decoding; wrong key must not succeed.
        thrown(Throwable)
    }

    def 'decrypts values written by jasypt BasicTextEncryptor'() {
        expect:
        encryptor.decrypt(encrypted) == plain

        where:
        plain                | encrypted
        'hello world'        | 'sYvsDA6GhTFy97SLuc8I3La99s6fkC8S'
        ''                   | 'cjMjCRe97tekyE0aIYo6QQ=='
        'café é résumé'      | 'HNy2JIeH4NnJdyzFJ9hS+lzfPBtbF43fyWrHL/l2YI0='
        CAFE_NFC             | 'T2lm+RPHDniI+WHIsk4jTA=='
        CAFE_NFD             | '1fK4n5KiCvZFeMvkXHvK+A=='
        'a b c\n!@#$%^&*()'  | '45XphHP8xnG0uQb3oIqmSgcAgpa2VwxdjoHQAbjfYMs='
    }

    def 'decrypt preserves the exact codepoint sequence (only the key is NFC-normalized)'() {
        expect:
        encryptor.decrypt('T2lm+RPHDniI+WHIsk4jTA==') == CAFE_NFC
        encryptor.decrypt('1fK4n5KiCvZFeMvkXHvK+A==') == CAFE_NFD
        CAFE_NFC != CAFE_NFD
    }

    def 'digest is deterministic and never exposes the plaintext'() {
        given:
        def plainDigest = encryptor.digest('secret', false)

        expect:
        plainDigest
        plainDigest != 'secret'
        encryptor.digest(encryptor.encrypt('secret')) == plainDigest
        encryptor.digest('sYvsDA6GhTFy97SLuc8I3La99s6fkC8S') == encryptor.digest('hello world', false)
        encryptor.digest('other', false) != plainDigest
        encryptor.digest(null) == null
    }

    def 'malformed input is rejected'() {
        when: encryptor.encrypt(null)
        then: thrown(IllegalArgumentException)

        when: encryptor.decrypt(null)
        then: thrown(IllegalArgumentException)

        when: 'too-short payload'
        encryptor.decrypt(Base64.encoder.encodeToString(new byte[4]))
        then: thrown(IllegalArgumentException)
    }
}
