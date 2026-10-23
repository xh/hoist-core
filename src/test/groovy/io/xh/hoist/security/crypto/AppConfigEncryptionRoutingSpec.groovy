/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security.crypto

import spock.lang.Specification

/**
 * Covers the read dispatch in {@code AppConfig.decryptPassword}: values carrying the
 * {@link AesTextCipher#FORMAT_PREFIX} marker decrypt via {@link AesTextCipher}, anything else via
 * {@link LegacyJasyptDecrypter}. Both are keyed by the same fixed `AppConfig` key.
 */
class AppConfigEncryptionRoutingSpec extends Specification {

    private static final String KEY = 'dsd899s_*)jsk9dsl2fd223hpdj32))I@333'

    private final AesTextCipher cipher = new AesTextCipher(KEY)
    private final LegacyJasyptDecrypter legacy = new LegacyJasyptDecrypter(KEY)

    private String decryptPassword(String value) {
        AesTextCipher.isHoistFormat(value) ? cipher.decrypt(value) : legacy.decrypt(value)
    }

    def 'values of either format decrypt to the correct plaintext'() {
        expect:
        decryptPassword(cipher.encrypt('newly-written-secret')) == 'newly-written-secret'

        and: 'fixtures captured from jasypt 1.9.3 BasicTextEncryptor under KEY'
        decryptPassword('sYvsDA6GhTFy97SLuc8I3La99s6fkC8S') == 'hello world'
        decryptPassword('0WLuQW9MxI0qPNOqTwVT60ZPGDl/QU0Y') == 'roundtrip-test'
    }

    def 'legacy ciphertext is never mistaken for the new format'() {
        expect:
        !AesTextCipher.isHoistFormat('sYvsDA6GhTFy97SLuc8I3La99s6fkC8S')
    }
}
