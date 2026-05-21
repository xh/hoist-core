/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security.crypto

import spock.lang.Specification

/**
 * Verifies the dispatch logic used by {@code AppConfig.decryptPassword} — new ciphertext routes
 * to {@link AesTextCipher}, legacy ciphertext (no hoist marker) routes to
 * {@link LegacyJasyptDecrypter}. This mirrors how a mixed-format database is read during an
 * in-place upgrade from hoist-core <= v40.
 */
class AppConfigEncryptionRoutingSpec extends Specification {

    private static final String APPCONFIG_PWD = 'dsd899s_*)jsk9dsl2fd223hpdj32))I@333'

    private final AesTextCipher cipher = new AesTextCipher(APPCONFIG_PWD)
    private final LegacyJasyptDecrypter legacy = new LegacyJasyptDecrypter(APPCONFIG_PWD)

    private String decryptPassword(String value) {
        AesTextCipher.isHoistFormat(value) ? cipher.decrypt(value) : legacy.decrypt(value)
    }

    def 'new-format ciphertext is decrypted via the AES cipher'() {
        given:
        def encoded = cipher.encrypt('my-fresh-secret')

        expect:
        encoded.startsWith(AesTextCipher.FORMAT_PREFIX)
        decryptPassword(encoded) == 'my-fresh-secret'
    }

    def 'legacy jasypt ciphertext falls back to the legacy decrypter'() {
        // Captured out-of-band from jasypt 1.9.3's BasicTextEncryptor using APPCONFIG_PWD
        expect:
        decryptPassword('sYvsDA6GhTFy97SLuc8I3La99s6fkC8S') == 'hello world'
        decryptPassword('0WLuQW9MxI0qPNOqTwVT60ZPGDl/QU0Y') == 'roundtrip-test'
    }

    def 'mixed-format reads return the correct plaintext regardless of write era'() {
        given:
        def newCt = cipher.encrypt('newly-written-secret')
        def legacyCt = 'sYvsDA6GhTFy97SLuc8I3La99s6fkC8S' // jasypt-written 'hello world'

        expect:
        decryptPassword(newCt) == 'newly-written-secret'
        decryptPassword(legacyCt) == 'hello world'
    }
}
