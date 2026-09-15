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
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * The MSAL client credential for {@link EntraIdService}, built from its soft config values,
 * along with the identifying details of the certificate behind it (when so configured).
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
 *
 * <p>Instances are built once and cached by the service - the bundle is parsed on construction
 * and not again, and {@link EntraIdService#clearCaches} discards the instance so a credential
 * swap takes effect without a restart.
 */
class EntraClientCredentials {

    /** Credential passed to the MSAL client. */
    final IClientCredential credential

    /** Certificate backing the credential - null when running on a client secret. */
    final X509Certificate certificate

    /**
     * Build a credential from the `xhEntraClientPfx` / `xhEntraClientPfxPassword` /
     * `xhEntraClientSecret` config values, any of which may be null when not configured.
     * @param pfxBase64 base64-encoded PKCS#12 bundle holding the certificate uploaded to the
     *      app registration, its chain (if any), and its private key. Embedded whitespace from
     *      line-wrapping pipelines is tolerated.
     * @param pfxPassword password for the bundle - null for a passwordless bundle.
     * @param secret client secret - the fallback when no certificate is configured.
     */
    static EntraClientCredentials create(String pfxBase64, String pfxPassword, String secret) {
        if (pfxBase64) {
            try {
                // MSAL owns all validation of the bundle - notably its requirement of exactly
                // one private key entry, enforced here before we read the certificate back out.
                return new EntraClientCredentials(
                    ClientCredentialFactory.createFromCertificate(pfxStream(pfxBase64), pfxPassword ?: ''),
                    parseCertificate(pfxBase64, pfxPassword)
                )
            } catch (Exception e) {
                throw new RuntimeException("Unable to load certificate credential from xhEntraClientPfx - confirm it holds the base64 of a valid PKCS#12 (.pfx/.p12) bundle and that xhEntraClientPfxPassword matches: ${e.message}", e)
            }
        }
        if (secret) {
            return new EntraClientCredentials(ClientCredentialFactory.createFromSecret(secret), null)
        }
        throw new RuntimeException('EntraIdService credentials not configured - provide either xhEntraClientPfx + xhEntraClientPfxPassword (recommended) or xhEntraClientSecret.')
    }

    /** Credential type in use - `certificate` or `clientSecret`. */
    String getAuthMode() {
        certificate ? 'certificate' : 'clientSecret'
    }

    /**
     * Identifying details of the certificate (public material only) for the Admin Console -
     * most usefully its expiry date and its thumbprint, which matches the certificate list on
     * the Entra ID app registration. Null when running on a client secret.
     */
    Map getCertificateStats() {
        if (!certificate) return null
        return [
            subject: certificate.subjectX500Principal.name,
            // SHA-1 hex, matching the "Thumbprint" column shown in the Azure portal.
            thumbprint: MessageDigest.getInstance('SHA-1').digest(certificate.encoded).encodeHex().toString().toUpperCase(),
            notAfter: certificate.notAfter
        ]
    }

    private EntraClientCredentials(IClientCredential credential, X509Certificate certificate) {
        this.credential = credential
        this.certificate = certificate
    }

    /** Extract the client certificate (public material only) from the PKCS#12 bundle. */
    private static X509Certificate parseCertificate(String pfxBase64, String pfxPassword) {
        KeyStore keyStore = KeyStore.getInstance('PKCS12')
        keyStore.load(pfxStream(pfxBase64), (pfxPassword ?: '').toCharArray())
        String alias = keyStore.aliases().toList().find { keyStore.isKeyEntry(it) }
        if (!alias) throw new RuntimeException('No private key entry found in the xhEntraClientPfx PKCS#12 bundle.')
        keyStore.getCertificate(alias) as X509Certificate
    }

    /** Decode the base64 bundle, tolerating embedded whitespace (MIME decoder). */
    private static InputStream pfxStream(String base64) {
        new ByteArrayInputStream(Base64.mimeDecoder.decode(base64.trim()))
    }
}
