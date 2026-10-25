/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security

import groovy.transform.CompileStatic

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PBEParameterSpec
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer

import static java.nio.charset.StandardCharsets.UTF_8

/**
 * Symmetric text encryption under a caller-supplied key. Used by {@code AppConfig} for
 * `pwd`-typed values.
 *
 * Drop-in replacement for jasypt's {@code BasicTextEncryptor}, which hoist-core used prior to
 * v44, with the same method names and the same wire format (PBEWithMD5AndDES, 1000 iterations,
 * 8-byte random salt prepended, Base64, NFC-normalized key). Existing values are read and written
 * unchanged. A stronger cipher is tracked alongside app-supplied keys in
 * https://github.com/xh/hoist-core/issues/627.
 */
@CompileStatic
class HoistTextEncryptor {

    private static final String ALGORITHM = 'PBEWithMD5AndDES'
    private static final int ITERATIONS = 1000
    private static final int SALT_BYTES = 8

    private final char[] key
    private final SecureRandom random = new SecureRandom()

    HoistTextEncryptor(String key) {
        if (!key) throw new IllegalArgumentException('key must be non-empty')
        this.key = Normalizer.normalize(key, Normalizer.Form.NFC).toCharArray()
    }

    /** Encrypt to a self-contained string with a fresh random salt per call. */
    String encrypt(String plain) {
        if (plain == null) throw new IllegalArgumentException('plain must not be null')

        byte[] salt = new byte[SALT_BYTES]
        random.nextBytes(salt)
        byte[] ct = cipher(Cipher.ENCRYPT_MODE, salt).doFinal(plain.getBytes(UTF_8))

        byte[] packed = new byte[salt.length + ct.length]
        System.arraycopy(salt, 0, packed, 0, salt.length)
        System.arraycopy(ct, 0, packed, salt.length, ct.length)
        return Base64.encoder.encodeToString(packed)
    }

    /** Decrypt a value written by {@link #encrypt}, or by jasypt's `BasicTextEncryptor` under the same key. */
    String decrypt(String encrypted) {
        if (encrypted == null) throw new IllegalArgumentException('encrypted must not be null')

        byte[] packed = Base64.decoder.decode(encrypted)
        if (packed.length <= SALT_BYTES) throw new IllegalArgumentException('Ciphertext is too short')

        byte[] salt = Arrays.copyOfRange(packed, 0, SALT_BYTES)
        byte[] ct = Arrays.copyOfRange(packed, SALT_BYTES, packed.length)
        return new String(cipher(Cipher.DECRYPT_MODE, salt).doFinal(ct), UTF_8)
    }

    /**
     * One-way, unsalted SHA-256 digest of a plaintext, so equal values can be compared across
     * environments without exposing them. Decrypts first if the value is in encrypted form.
     */
    String digest(String value, boolean isEncrypted = true) {
        if (value == null) return null
        String plain = isEncrypted ? decrypt(value) : value
        return Base64.encoder.encodeToString(MessageDigest.getInstance('SHA-256').digest(plain.getBytes(UTF_8)))
    }

    //------------------------
    // Implementation
    //------------------------
    private Cipher cipher(int mode, byte[] salt) {
        PBEKeySpec keySpec = new PBEKeySpec(key)
        try {
            SecretKey secret = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(keySpec)
            Cipher ret = Cipher.getInstance(ALGORITHM)
            ret.init(mode, secret, new PBEParameterSpec(salt, ITERATIONS))
            return ret
        } finally {
            keySpec.clearPassword()
        }
    }
}
