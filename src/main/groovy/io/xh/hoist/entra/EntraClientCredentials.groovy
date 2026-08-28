/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.entra

import com.microsoft.aad.msal4j.ClientCredentialFactory
import com.microsoft.aad.msal4j.IClientCredential

import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Builds the MSAL client credential for {@link EntraIdService} from its soft config values.
 *
 * <p>A configured certificate takes precedence over a client secret - it is the stronger
 * credential (the private key never leaves the server; MSAL signs a short-lived JWT assertion
 * with it), and Microsoft recommends certificates over secrets for production use.
 *
 * <p>The certificate credential is supplied as a base64-encoded PKCS#12 (.pfx/.p12) bundle -
 * the standard container for a certificate, its chain, and its private key in transit. As
 * base64 it travels safely through secret managers and environment variables, and parsing is
 * handled entirely by MSAL and the JDK's built-in PKCS#12 keystore support. Convert PEM
 * material with `openssl pkcs12 -export -in cert.pem -inkey key.pem | base64`.
 */
class EntraClientCredentials {

    /**
     * Build a credential from the `xhEntraClientPfx` / `xhEntraClientPfxPassword` /
     * `xhEntraClientSecret` config values, any of which may be null when not configured.
     * @param pfxBase64 base64-encoded PKCS#12 bundle holding the certificate uploaded to the
     *      app registration, its chain (if any), and its private key. Embedded whitespace from
     *      line-wrapping pipelines is tolerated.
     * @param pfxPassword password for the bundle - null for a passwordless bundle.
     * @param secret client secret - the fallback when no certificate is configured.
     */
    static IClientCredential create(String pfxBase64, String pfxPassword, String secret) {
        if (pfxBase64) {
            try {
                return ClientCredentialFactory.createFromCertificate(pfxStream(pfxBase64), pfxPassword ?: '')
            } catch (Exception e) {
                throw new RuntimeException("Unable to load certificate credential from xhEntraClientPfx - confirm it holds the base64 of a valid PKCS#12 (.pfx/.p12) bundle and that xhEntraClientPfxPassword matches: ${e.message}", e)
            }
        }
        if (secret) {
            return ClientCredentialFactory.createFromSecret(secret)
        }
        throw new RuntimeException('EntraIdService credentials not configured - provide either xhEntraClientPfx + xhEntraClientPfxPassword (recommended) or xhEntraClientSecret.')
    }

    /**
     * Extract the client certificate (public material only) from the configured PKCS#12
     * bundle - e.g. for Admin Console visibility of its subject, thumbprint, and expiry.
     */
    static X509Certificate parseCertificate(String pfxBase64, String pfxPassword) {
        char[] password = (pfxPassword ?: '').toCharArray()
        KeyStore keyStore = KeyStore.getInstance('PKCS12')
        keyStore.load(pfxStream(pfxBase64), password)
        String alias = keyStore.aliases().toList().find { keyStore.isKeyEntry(it) }
        if (!alias) throw new RuntimeException('No private key entry found in the xhEntraClientPfx PKCS#12 bundle.')
        keyStore.getCertificate(alias) as X509Certificate
    }

    /** Decode the base64 bundle, tolerating embedded whitespace (MIME decoder). */
    private static InputStream pfxStream(String base64) {
        new ByteArrayInputStream(Base64.mimeDecoder.decode(base64.trim()))
    }
}
