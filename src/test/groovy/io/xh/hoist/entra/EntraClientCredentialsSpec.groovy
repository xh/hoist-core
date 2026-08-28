/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.entra

import com.microsoft.aad.msal4j.IClientCertificate
import com.microsoft.aad.msal4j.IClientSecret
import spock.lang.Specification

class EntraClientCredentialsSpec extends Specification {

    /**
     * Throwaway PKCS#12 fixtures, generated for these tests only: a self-signed certificate
     * (CN=hoist-core-test-fixture) and its private key, as an openssl-exported .pfx, base64.
     * TEST_PFX is protected by TEST_PFX_PASSWORD; TEST_PFX_NOPASS by the empty password.
     */
    static final String TEST_PFX_PASSWORD = 'fixture-password'
    static final String TEST_PFX = '''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'''

    static final String TEST_PFX_NOPASS = '''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
BBBLkPwflr/PV5ubXEMROE9+AgIIAA=='''

    def 'builds a certificate credential from a base64 PKCS#12 bundle and password'() {
        when:
        def credential = EntraClientCredentials.create(singleLine(TEST_PFX), TEST_PFX_PASSWORD, null)

        then:
        credential instanceof IClientCertificate
    }

    def 'accepts base64 with embedded newlines, as delivered by wrapping pipelines'() {
        when:
        def credential = EntraClientCredentials.create(TEST_PFX, TEST_PFX_PASSWORD, null)

        then:
        credential instanceof IClientCertificate
    }

    def 'accepts a passwordless PKCS#12 bundle when no password is configured'() {
        when:
        def credential = EntraClientCredentials.create(TEST_PFX_NOPASS, null, null)

        then:
        credential instanceof IClientCertificate
    }

    def 'builds a secret credential when only a secret is configured'() {
        when:
        def credential = EntraClientCredentials.create(null, null, 'my-secret')

        then:
        credential instanceof IClientSecret
        (credential as IClientSecret).clientSecret() == 'my-secret'
    }

    def 'prefers the certificate credential when both PKCS#12 and secret are configured'() {
        when:
        def credential = EntraClientCredentials.create(TEST_PFX, TEST_PFX_PASSWORD, 'my-secret')

        then:
        credential instanceof IClientCertificate
    }

    def 'throws naming the configs on a wrong PKCS#12 password'() {
        when:
        EntraClientCredentials.create(TEST_PFX, 'wrong-password', null)

        then:
        def e = thrown(RuntimeException)
        e.message.contains('xhEntraClientPfx')
    }

    def 'throws naming the credential configs when nothing is configured'() {
        when:
        EntraClientCredentials.create(null, null, null)

        then:
        def e = thrown(RuntimeException)
        e.message.contains('xhEntraClientSecret')
        e.message.contains('xhEntraClientPfx')
    }

    def 'parses the certificate from the bundle for admin visibility'() {
        when:
        def cert = EntraClientCredentials.parseCertificate(TEST_PFX, TEST_PFX_PASSWORD)

        then:
        cert.subjectX500Principal.name == 'CN=hoist-core-test-fixture'
    }

    private static String singleLine(String base64) {
        base64.replaceAll(/\s/, '')
    }
}
