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
 * algorithms. Used to verify and migrate legacy values produced by hoist-core <= v40 and apps
 * whose User domains historically depended on jasypt directly.
 *
 * <p>This class exists strictly to support a one-release transition window: new ciphertexts and
 * hashes are written by {@link AesTextCipher} and
 * {@link io.xh.hoist.security.HoistPasswordEncoder}, while legacy values continue to be readable
 * via this helper until they have been organically migrated (re-saving a `pwd` config, logging
 * in as a local user, etc.).
 *
 * <h3>Compatibility notes</h3>
 * <ul>
 *   <li><strong>Text decrypt</strong>: PBEWithMD5AndDES, 1000 iterations, 8-byte random salt
 *       prepended to ciphertext, Base64-encoded. Matches jasypt's
 *       {@code BasicTextEncryptor.decrypt(String)}.</li>
 *   <li><strong>Password check</strong>: MD5, 1000 iterations, 8-byte salt prepended to digest,
 *       Base64-encoded. Matches jasypt's {@code BasicPasswordEncryptor.checkPassword(plain, encoded)}.</li>
 * </ul>
 *
 * <p>Both algorithms apply NFC Unicode normalization to inputs before processing, mirroring
 * jasypt. (We use {@link java.text.Normalizer} directly here, which on its own is not the
 * codepath that breaks under Spring Boot's launcher classloader — jasypt's failure is in its
 * reflective wrapper around the same JDK API.)
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

    /**
     * Decrypt a value previously produced by jasypt's {@code BasicTextEncryptor.encrypt(plain)}
     * with the same password. The input is the raw Base64 string jasypt wrote into the
     * database — no marker prefix.
     */
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

    /**
     * True if the given plaintext password, hashed with jasypt's {@code BasicPasswordEncryptor}
     * algorithm using the salt extracted from {@code legacyEncoded}, matches the encoded digest.
     */
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

    /**
     * Heuristic: the given encoded string has the shape of a jasypt
     * {@code BasicPasswordEncryptor} digest (Base64-encoded 24 raw bytes = 8 salt + 16 MD5).
     * Used to decide whether to attempt legacy verification or fall through.
     */
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
