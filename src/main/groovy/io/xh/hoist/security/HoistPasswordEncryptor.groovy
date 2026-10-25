/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security

import groovy.transform.CompileStatic
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

import java.security.MessageDigest
import java.text.Normalizer

import static java.nio.charset.StandardCharsets.UTF_8

/**
 * BCrypt password hashing for app `User` domain classes that store local user passwords.
 *
 * Drop-in replacement for jasypt's {@code BasicPasswordEncryptor}.
 *
 * {@link #checkPassword} verifies both BCrypt hashes written by this class and hashes written by
 * jasypt's {@code BasicPasswordEncryptor}, so fully backward compatible with pre v44 passwords
 * encoded via jasypt.
 */
@CompileStatic
class HoistPasswordEncryptor {

    private static final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder()

    /** Hash a plaintext password with BCrypt. Returns null for null or empty input. */
    String encryptPassword(String plain) {
        plain ? bcrypt.encode(plain) : null
    }

    /** Verify a plaintext against a BCrypt or jasypt hash. False for null or empty input. */
    boolean checkPassword(String plain, String encrypted) {
        if (!plain || !encrypted) return false
        if (isBCrypt(encrypted)) {
            try {
                return bcrypt.matches(plain, encrypted)
            } catch (IllegalArgumentException ignored) {
                return false
            }
        }
        return checkJasyptPassword(plain, encrypted)
    }

    //------------------------
    // Implementation
    //------------------------
    private static boolean isBCrypt(String value) {
        value.length() == 60 && value.startsWith('$2')
    }

    //------------------------------------------------------------------------------------------
    // Reproduces jasypt 1.9.3's default BasicPasswordEncryptor digest for existing hashes: MD5
    // over (8-byte random salt + NFC-normalized UTF-8 plaintext), re-digested 1000 times in total
    // without re-applying the salt, stored as Base64 of (salt + digest). Never used for new hashes.
    //------------------------------------------------------------------------------------------
    private static final int JASYPT_SALT_BYTES = 8
    private static final int JASYPT_DIGEST_BYTES = 16
    private static final int JASYPT_ITERATIONS = 1000

    private static boolean checkJasyptPassword(String plain, String encrypted) {
        byte[] packed = decodeBase64(encrypted)
        if (packed == null || packed.length != JASYPT_SALT_BYTES + JASYPT_DIGEST_BYTES) return false

        byte[] salt = packed[0..<JASYPT_SALT_BYTES] as byte[]
        byte[] expected = packed[JASYPT_SALT_BYTES..-1] as byte[]
        byte[] msg = Normalizer.normalize(plain, Normalizer.Form.NFC).getBytes(UTF_8)

        MessageDigest md = MessageDigest.getInstance('MD5')
        md.update(salt)
        byte[] digest = md.digest(msg)
        for (int i = 1; i < JASYPT_ITERATIONS; i++) {
            md.reset()
            digest = md.digest(digest)
        }
        return MessageDigest.isEqual(expected, digest)
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.decoder.decode(value)
        } catch (IllegalArgumentException ignored) {
            return null
        }
    }
}
