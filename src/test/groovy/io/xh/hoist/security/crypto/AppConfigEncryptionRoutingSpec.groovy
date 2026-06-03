/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security.crypto

import spock.lang.Specification

/**
 * Mirrors the dispatch logic in {@code AppConfig.encryptIfPwd} / {@code AppConfig.decryptPassword}:
 *
 * <ul>
 *   <li>Hoist-format ciphertext routes to {@link AesTextCipher} (the active, app-keyed cipher).</li>
 *   <li>Unmarked ciphertext routes to {@link LegacyJasyptDecrypter} (read-only migration shim
 *       keyed by the legacy source-visible value).</li>
 *   <li>Encrypt requires a configured active cipher; without one, writes fail closed.</li>
 *   <li>Hoist-format ciphertext cannot be decrypted without a configured active cipher.</li>
 * </ul>
 *
 * AppConfig itself can't be exercised here without a Grails context (see src/test/groovy/README.md);
 * this spec keeps the routing rules under explicit unit coverage so AppConfig's static-init code
 * stays straightforward to audit.
 */
class AppConfigEncryptionRoutingSpec extends Specification {

    // Pre-v41 source-visible obfuscation key — used ONLY to read legacy ciphertexts.
    private static final String LEGACY_KEY = 'dsd899s_*)jsk9dsl2fd223hpdj32))I@333'

    // High-entropy app-supplied key — what a v41+ deployment would inject via instance config.
    private static final String APP_KEY = 'simulated-secrets-manager-value-with-real-entropy'

    private final LegacyJasyptDecrypter legacy = new LegacyJasyptDecrypter(LEGACY_KEY)

    private String decryptPassword(AesTextCipher activeCipher, String value) {
        if (AesTextCipher.isHoistFormat(value)) {
            if (activeCipher == null) {
                throw new IllegalStateException('no active cipher configured')
            }
            return activeCipher.decrypt(value)
        }
        return legacy.decrypt(value)
    }

    private String encryptPassword(AesTextCipher activeCipher, String value) {
        if (activeCipher == null) {
            throw new IllegalStateException('no active cipher configured')
        }
        return activeCipher.encrypt(value)
    }

    def 'new-format ciphertext is decrypted via the active cipher'() {
        given:
        def cipher = new AesTextCipher(APP_KEY)
        def encoded = cipher.encrypt('my-fresh-secret')

        expect:
        encoded.startsWith(AesTextCipher.FORMAT_PREFIX)
        decryptPassword(cipher, encoded) == 'my-fresh-secret'
    }

    def 'legacy jasypt ciphertext reads via the legacy decrypter regardless of active-cipher state'() {
        // Captured out-of-band from jasypt 1.9.3's BasicTextEncryptor under LEGACY_KEY.
        given:
        def appCipher = new AesTextCipher(APP_KEY)

        expect:
        decryptPassword(appCipher, 'sYvsDA6GhTFy97SLuc8I3La99s6fkC8S') == 'hello world'
        decryptPassword(null,      'sYvsDA6GhTFy97SLuc8I3La99s6fkC8S') == 'hello world'
        decryptPassword(appCipher, '0WLuQW9MxI0qPNOqTwVT60ZPGDl/QU0Y') == 'roundtrip-test'
    }

    def 'mixed-format DB reads return the correct plaintext regardless of write era'() {
        given:
        def cipher = new AesTextCipher(APP_KEY)
        def newCt = cipher.encrypt('newly-written-secret')
        def legacyCt = 'sYvsDA6GhTFy97SLuc8I3La99s6fkC8S' // jasypt-written 'hello world'

        expect:
        decryptPassword(cipher, newCt) == 'newly-written-secret'
        decryptPassword(cipher, legacyCt) == 'hello world'
    }

    def 'write attempts fail closed when no active cipher is configured'() {
        when:
        encryptPassword(null, 'anything')

        then:
        thrown(IllegalStateException)
    }

    def 'hoist-format reads fail when no active cipher is configured'() {
        given:
        def cipher = new AesTextCipher(APP_KEY)
        def encoded = cipher.encrypt('written-under-app-key')

        when:
        decryptPassword(null, encoded)

        then:
        thrown(IllegalStateException)
    }
}
