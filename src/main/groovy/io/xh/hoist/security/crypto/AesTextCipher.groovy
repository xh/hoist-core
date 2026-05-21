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
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Symmetric AES-256-GCM text encryption with a PBKDF2-derived key, used internally by
 * {@link io.xh.hoist.config.AppConfig} to obfuscate `pwd`-typed config values at rest. Output
 * carries the {@link #FORMAT_PREFIX} marker so callers can distinguish it from legacy values
 * decryptable via {@link LegacyJasyptDecrypter}.
 */
@CompileStatic
final class AesTextCipher {

    /** Marker prefix on output ciphertext — identifies hoist-core v1 AES-GCM format. */
    static final String FORMAT_PREFIX = '$hoist-aes1$'

    private static final String KEY_ALGORITHM = 'PBKDF2WithHmacSHA256'
    private static final String CIPHER_ALGORITHM = 'AES/GCM/NoPadding'
    private static final int KEY_LENGTH_BITS = 256
    private static final int PBKDF2_ITERATIONS = 65_536
    private static final int SALT_LENGTH_BYTES = 16
    private static final int IV_LENGTH_BYTES = 12
    private static final int GCM_TAG_LENGTH_BITS = 128

    private final char[] password
    private final SecureRandom random = new SecureRandom()

    AesTextCipher(String password) {
        if (!password) throw new IllegalArgumentException('password must be non-empty')
        this.password = password.toCharArray()
    }

    /** Encrypt to a self-contained Base64 string with a freshly-random salt and IV per call. */
    String encrypt(String plaintext) {
        if (plaintext == null) throw new IllegalArgumentException('plaintext must not be null')

        byte[] salt = new byte[SALT_LENGTH_BYTES]
        random.nextBytes(salt)
        byte[] iv = new byte[IV_LENGTH_BYTES]
        random.nextBytes(iv)

        SecretKey key = deriveKey(salt)
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))

        // Layout: [salt(16)][iv(12)][ciphertext+gcm_tag(variable)]
        byte[] packed = new byte[salt.length + iv.length + ct.length]
        System.arraycopy(salt, 0, packed, 0, salt.length)
        System.arraycopy(iv, 0, packed, salt.length, iv.length)
        System.arraycopy(ct, 0, packed, salt.length + iv.length, ct.length)

        return FORMAT_PREFIX + Base64.encoder.encodeToString(packed)
    }

    /** Decrypt a value produced by {@link #encrypt}; throws if the format prefix is missing. */
    String decrypt(String ciphertext) {
        if (ciphertext == null) throw new IllegalArgumentException('ciphertext must not be null')
        if (!isHoistFormat(ciphertext)) {
            throw new IllegalArgumentException(
                "Ciphertext does not carry the expected '$FORMAT_PREFIX' prefix"
            )
        }

        byte[] packed = Base64.decoder.decode(ciphertext.substring(FORMAT_PREFIX.length()))
        if (packed.length < SALT_LENGTH_BYTES + IV_LENGTH_BYTES + 1) {
            throw new IllegalArgumentException('Ciphertext payload is too short')
        }

        byte[] salt = new byte[SALT_LENGTH_BYTES]
        byte[] iv = new byte[IV_LENGTH_BYTES]
        byte[] ct = new byte[packed.length - SALT_LENGTH_BYTES - IV_LENGTH_BYTES]
        System.arraycopy(packed, 0, salt, 0, SALT_LENGTH_BYTES)
        System.arraycopy(packed, SALT_LENGTH_BYTES, iv, 0, IV_LENGTH_BYTES)
        System.arraycopy(packed, SALT_LENGTH_BYTES + IV_LENGTH_BYTES, ct, 0, ct.length)

        SecretKey key = deriveKey(salt)
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        byte[] pt = cipher.doFinal(ct)
        return new String(pt, StandardCharsets.UTF_8)
    }

    /** True if the given value carries this cipher's format marker. */
    static boolean isHoistFormat(String value) {
        return value != null && value.startsWith(FORMAT_PREFIX)
    }

    private SecretKey deriveKey(byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        try {
            byte[] keyBytes = SecretKeyFactory.getInstance(KEY_ALGORITHM).generateSecret(spec).encoded
            return new SecretKeySpec(keyBytes, 'AES')
        } finally {
            spec.clearPassword()
        }
    }
}
