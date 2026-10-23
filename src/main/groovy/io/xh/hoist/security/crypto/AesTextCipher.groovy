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
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

import static java.nio.charset.StandardCharsets.UTF_8

/**
 * Symmetric AES-256-GCM text encryption. GCM is authenticated, so tampered ciphertext fails on
 * decrypt. Output is {@link #FORMAT_PREFIX} + Base64([iv][ciphertext+tag]) with a fresh random IV
 * per call. The marker lets {@link io.xh.hoist.config.AppConfig} distinguish these values from
 * pre-v44 jasypt values, which it reads via {@link LegacyJasyptDecrypter}.
 *
 * <p>The AES key is the SHA-256 of the supplied key string, derived once at construction.
 */
@CompileStatic
final class AesTextCipher {

    /** Marker prefix on output ciphertext. `$` cannot appear in Base64, so legacy values never collide. */
    static final String FORMAT_PREFIX = '$hoist-aes1$'

    private static final String CIPHER_ALGORITHM = 'AES/GCM/NoPadding'
    private static final int IV_LENGTH_BYTES = 12
    private static final int GCM_TAG_LENGTH_BITS = 128

    private final SecretKey key
    private final SecureRandom random = new SecureRandom()

    AesTextCipher(String key) {
        if (!key) throw new IllegalArgumentException('key must be non-empty')
        byte[] keyBytes = MessageDigest.getInstance('SHA-256').digest(key.getBytes(UTF_8))
        this.key = new SecretKeySpec(keyBytes, 'AES')
    }

    /** Encrypt to a self-contained string with a freshly-random IV per call. */
    String encrypt(String plaintext) {
        if (plaintext == null) throw new IllegalArgumentException('plaintext must not be null')

        byte[] iv = new byte[IV_LENGTH_BYTES]
        random.nextBytes(iv)
        byte[] ct = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plaintext.getBytes(UTF_8))

        byte[] packed = new byte[iv.length + ct.length]
        System.arraycopy(iv, 0, packed, 0, iv.length)
        System.arraycopy(ct, 0, packed, iv.length, ct.length)
        return FORMAT_PREFIX + Base64.encoder.encodeToString(packed)
    }

    /** Decrypt a value produced by {@link #encrypt}; throws if the format prefix is missing. */
    String decrypt(String ciphertext) {
        if (!isHoistFormat(ciphertext)) {
            throw new IllegalArgumentException("Ciphertext does not carry the expected '$FORMAT_PREFIX' prefix")
        }

        byte[] packed = Base64.decoder.decode(ciphertext.substring(FORMAT_PREFIX.length()))
        if (packed.length <= IV_LENGTH_BYTES) throw new IllegalArgumentException('Ciphertext payload is too short')

        byte[] iv = Arrays.copyOfRange(packed, 0, IV_LENGTH_BYTES)
        byte[] ct = Arrays.copyOfRange(packed, IV_LENGTH_BYTES, packed.length)
        return new String(cipher(Cipher.DECRYPT_MODE, iv).doFinal(ct), UTF_8)
    }

    /** True if the given value carries this cipher's format marker. */
    static boolean isHoistFormat(String value) {
        return value != null && value.startsWith(FORMAT_PREFIX)
    }

    private Cipher cipher(int mode, byte[] iv) {
        Cipher ret = Cipher.getInstance(CIPHER_ALGORITHM)
        ret.init(mode, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return ret
    }
}
