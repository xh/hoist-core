/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security.crypto

import spock.lang.Specification

/**
 * Verifies {@link LegacyJasyptDecrypter} correctly reproduces the decode side of jasypt 1.9.3's
 * {@code BasicTextEncryptor} and {@code BasicPasswordEncryptor} default algorithms.
 *
 * <p>Test vectors below were captured out-of-band from jasypt 1.9.3 itself (the same
 * implementation that wrote them into existing production databases). They are stable encoded
 * outputs — each random-salt-prefixed Base64 string decrypts deterministically against the
 * given password / verifies against the given plaintext.
 */
class LegacyJasyptDecrypterSpec extends Specification {

    // Same password historically hard-coded in AppConfig.groovy for `pwd` value encryption.
    private static final String APPCONFIG_PWD = 'dsd899s_*)jsk9dsl2fd223hpdj32))I@333'

    def 'decrypts known jasypt BasicTextEncryptor fixtures'() {
        given:
        def decrypter = new LegacyJasyptDecrypter(APPCONFIG_PWD)

        expect:
        decrypter.decrypt(ciphertext) == plaintext

        where:
        ciphertext                                       | plaintext
        'sYvsDA6GhTFy97SLuc8I3La99s6fkC8S'               | 'hello world'
        '7j0sw7gOeG8Lr/bZHxARBw=='                       | ''
        'HNy2JIeH4NnJdyzFJ9hS+lzfPBtbF43fyWrHL/l2YI0='   | 'café é résumé'
        '0WLuQW9MxI0qPNOqTwVT60ZPGDl/QU0Y'               | 'roundtrip-test'
    }

    def 'rejects too-short ciphertext'() {
        given:
        def decrypter = new LegacyJasyptDecrypter(APPCONFIG_PWD)

        when:
        decrypter.decrypt(Base64.encoder.encodeToString(new byte[4]))

        then:
        thrown(IllegalArgumentException)
    }

    def 'matches known jasypt BasicPasswordEncryptor digest fixtures'() {
        // BasicPasswordEncryptor produces a per-call random salt -- each line below is a different
        // encoded form of the SAME plaintext, validating that salt is correctly extracted.
        expect:
        LegacyJasyptDecrypter.matchesLegacyPasswordHash(plain, encoded)

        where:
        plain             | encoded
        'secret'          | 'Dn9nYr0xRJwMqmobJYydHNEVGYXDuuRv'
        'secret'          | '4ZciBiDn8vFKS7KfWKDtrPI2zh65npVq'
        'secret'          | '3vCC8qlzR40smcGfXLA795dxcnyRNmoS'
        'secret'          | 'mWwYfNJOQyvOVIFEVFJkgro8OyZjvNM4'
        'password'        | 'FJ4Cw1xTfDMVAHFv1bmv7OZmSsKnieZD'
        'playwright-test' | '3hrIc7ebBL/NxnkkVk0OWUAMyxRVld3U'
    }

    def 'rejects wrong plaintext for legacy digest'() {
        expect:
        !LegacyJasyptDecrypter.matchesLegacyPasswordHash('wrong', 'Dn9nYr0xRJwMqmobJYydHNEVGYXDuuRv')
        !LegacyJasyptDecrypter.matchesLegacyPasswordHash('SECRET', 'Dn9nYr0xRJwMqmobJYydHNEVGYXDuuRv')
        !LegacyJasyptDecrypter.matchesLegacyPasswordHash('', 'Dn9nYr0xRJwMqmobJYydHNEVGYXDuuRv')
        !LegacyJasyptDecrypter.matchesLegacyPasswordHash(null, 'Dn9nYr0xRJwMqmobJYydHNEVGYXDuuRv')
        !LegacyJasyptDecrypter.matchesLegacyPasswordHash('secret', null)
    }

    def 'looksLikeLegacyPasswordHash recognises 24-byte base64 strings'() {
        expect:
        LegacyJasyptDecrypter.looksLikeLegacyPasswordHash('Dn9nYr0xRJwMqmobJYydHNEVGYXDuuRv')
        LegacyJasyptDecrypter.looksLikeLegacyPasswordHash('3hrIc7ebBL/NxnkkVk0OWUAMyxRVld3U')

        and: 'rejects non-base64, wrong length, null, BCrypt-shaped'
        !LegacyJasyptDecrypter.looksLikeLegacyPasswordHash(null)
        !LegacyJasyptDecrypter.looksLikeLegacyPasswordHash('')
        !LegacyJasyptDecrypter.looksLikeLegacyPasswordHash('not-base64-!!!')
        !LegacyJasyptDecrypter.looksLikeLegacyPasswordHash('$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123')
        !LegacyJasyptDecrypter.looksLikeLegacyPasswordHash(Base64.encoder.encodeToString(new byte[16]))
    }
}
