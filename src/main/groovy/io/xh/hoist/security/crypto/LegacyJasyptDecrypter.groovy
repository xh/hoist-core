/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security.crypto

import groovy.transform.CompileStatic

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PBEParameterSpec
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Read-only, pure-JDK reproduction of jasypt 1.9.3's default symmetric text encryption
 * ({@code BasicTextEncryptor}) and one-way password hash ({@code BasicPasswordEncryptor})
 * algorithms. Supports a one-release transition window for legacy values until they migrate
 * organically (re-saving a `pwd` config, logging in as a local user, etc.).
 *
 * <h3>Intended scope — migration shim only</h3>
 * The sole purpose of this class is reading pre-v41 `pwd` AppConfig ciphertexts and verifying
 * legacy local-user password hashes long enough for them to be re-written in modern formats
 * (via {@link AesTextCipher} for `pwd` values; BCrypt via
 * {@link io.xh.hoist.security.HoistPasswordEncoder} for user passwords). The algorithms
 * reproduced here (PBE-MD5-DES, MD5+8-byte-salt) are obsolete by modern standards and offer
 * no meaningful security. The pre-v41 `pwd` AppConfig case additionally used a fixed
 * source-visible key — pre-upgrade `pwd` ciphertexts must be treated as if they were stored
 * in plaintext. Do not adopt this class for any new use case.
 *
 * <h3>Cipher / digest parameters (must match jasypt 1.9.3 exactly)</h3>
 * <ul>
 *   <li><b>Text decrypt</b> — algorithm {@code PBEWithMD5AndDES}; key derivation per PKCS#5 PBES1
 *       (MD5, 1000 iterations); 8-byte random salt prepended to ciphertext; output
 *       Base64-encoded; no format marker. Mirrors
 *       {@code org.jasypt.encryption.pbe.StandardPBEStringEncryptor} with default config.</li>
 *   <li><b>Password digest</b> — MD5, 1000 iterations; 8-byte random salt prepended to the
 *       digest output; digest is iterated WITHOUT re-prepending salt after the first round (see
 *       {@code org.jasypt.digest.StandardByteDigester.digest}); resulting 24 raw bytes
 *       (8 salt + 16 MD5) Base64-encoded.</li>
 *   <li><b>Unicode</b> — both algorithms apply NFC normalization to character inputs before
 *       processing, matching jasypt's
 *       {@code org.jasypt.normalization.Normalizer.normalizeWithJavaNormalizer}. We invoke
 *       {@link java.text.Normalizer} directly here; jasypt's failure on JDK 21+ is in its
 *       reflective wrapper around the same JDK API.</li>
 * </ul>
 */
@CompileStatic
final class LegacyJasyptDecrypter {

    private static final String PBE_ALGORITHM = 'PBEWithMD5AndDES'
    private static final int PBE_ITERATIONS = 1000
    private static final int PBE_SALT_BYTES = 8

    private static final String DIGEST_ALGORITHM = 'MD5'
    private static final int DIGEST_ITERATIONS = 1000
    private static final int DIGEST_SALT_BYTES = 8
    private static final int DIGEST_LENGTH_BYTES = 16 // MD5 output size

    private final char[] password

    LegacyJasyptDecrypter(String password) {
        if (!password) throw new IllegalArgumentException('password must be non-empty')
        this.password = password.toCharArray()
    }

    /** Decrypt a Base64 string produced by jasypt's {@code BasicTextEncryptor.encrypt(plain)}. */
    String decrypt(String encodedCiphertext) {
        if (encodedCiphertext == null) throw new IllegalArgumentException('ciphertext must not be null')

        byte[] packed = Base64.decoder.decode(encodedCiphertext)
        if (packed.length <= PBE_SALT_BYTES) {
            throw new IllegalArgumentException('Legacy jasypt ciphertext is too short')
        }
        byte[] salt = new byte[PBE_SALT_BYTES]
        byte[] ct = new byte[packed.length - PBE_SALT_BYTES]
        System.arraycopy(packed, 0, salt, 0, PBE_SALT_BYTES)
        System.arraycopy(packed, PBE_SALT_BYTES, ct, 0, ct.length)

        char[] normalizedPwd = nfcNormalize(password)
        try {
            PBEKeySpec keySpec = new PBEKeySpec(normalizedPwd)
            try {
                SecretKey key = SecretKeyFactory.getInstance(PBE_ALGORITHM).generateSecret(keySpec)
                Cipher cipher = Cipher.getInstance(PBE_ALGORITHM)
                cipher.init(Cipher.DECRYPT_MODE, key, new PBEParameterSpec(salt, PBE_ITERATIONS))
                byte[] plain = cipher.doFinal(ct)
                return new String(plain, StandardCharsets.UTF_8)
            } finally {
                keySpec.clearPassword()
            }
        } finally {
            java.util.Arrays.fill(normalizedPwd, '\0' as char)
        }
    }

    //----------------------------------------------------------------
    // Legacy password (one-way digest) verification
    //----------------------------------------------------------------

    /** True if hashing {@code plain} with the salt embedded in {@code legacyEncoded} matches it. */
    static boolean matchesLegacyPasswordHash(String plain, String legacyEncoded) {
        if (plain == null || legacyEncoded == null) return false
        byte[] packed
        try {
            packed = Base64.decoder.decode(legacyEncoded)
        } catch (IllegalArgumentException ignored) {
            return false
        }
        if (packed.length != DIGEST_SALT_BYTES + DIGEST_LENGTH_BYTES) return false

        byte[] salt = new byte[DIGEST_SALT_BYTES]
        byte[] expected = new byte[DIGEST_LENGTH_BYTES]
        System.arraycopy(packed, 0, salt, 0, DIGEST_SALT_BYTES)
        System.arraycopy(packed, DIGEST_SALT_BYTES, expected, 0, DIGEST_LENGTH_BYTES)

        byte[] actual = computeLegacyDigest(plain, salt)
        return MessageDigest.isEqual(expected, actual)
    }

    /** Shape check: Base64 of exactly 24 raw bytes (8 salt + 16 MD5). */
    static boolean looksLikeLegacyPasswordHash(String encoded) {
        if (encoded == null || encoded.isEmpty()) return false
        byte[] decoded
        try {
            decoded = Base64.decoder.decode(encoded)
        } catch (IllegalArgumentException ignored) {
            return false
        }
        return decoded.length == DIGEST_SALT_BYTES + DIGEST_LENGTH_BYTES
    }

    private static byte[] computeLegacyDigest(String plain, byte[] salt) {
        char[] normalized = nfcNormalize(plain.toCharArray())
        try {
            byte[] msg = new String(normalized).getBytes(StandardCharsets.UTF_8)
            byte[] combined = new byte[salt.length + msg.length]
            System.arraycopy(salt, 0, combined, 0, salt.length)
            System.arraycopy(msg, 0, combined, salt.length, msg.length)

            MessageDigest md = MessageDigest.getInstance(DIGEST_ALGORITHM)
            byte[] digest = md.digest(combined)
            // jasypt re-digests its prior output (without re-prepending salt) for the remaining
            // iterations - see StandardByteDigester.digest().
            for (int i = 1; i < DIGEST_ITERATIONS; i++) {
                md.reset()
                digest = md.digest(digest)
            }
            return digest
        } finally {
            java.util.Arrays.fill(normalized, '\0' as char)
        }
    }

    private static char[] nfcNormalize(char[] input) {
        String normalized = Normalizer.normalize(CharBuffer.wrap(input), Normalizer.Form.NFC)
        return normalized.toCharArray()
    }
}
