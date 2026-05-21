/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security.crypto

import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One-way SHA-256 digester used internally by {@link io.xh.hoist.config.AppConfig} to render a
 * comparable, non-reversible fingerprint of `pwd`-typed config values in the admin UI's
 * config-differ display.
 *
 * <p>This is <strong>not</strong> a password storage primitive — it is used purely to produce a
 * stable, opaque token so the admin UI can detect whether two `pwd` values are identical
 * without revealing the underlying plaintext. For user-password hashing, see
 * {@link io.xh.hoist.security.HoistPasswordEncoder}.
 *
 * <p>A random salt is generated once per instance and then reused for every call, so two calls
 * with the same input on the same digester instance produce identical output (suitable for
 * within-process diffing).
 */
@CompileStatic
final class SaltedSha256Digester {

    private final byte[] salt

    SaltedSha256Digester() {
        this.salt = new byte[16]
        new SecureRandom().nextBytes(salt)
    }

    /** Returns a Base64-encoded SHA-256 digest of the given input mixed with this instance's salt. */
    String digest(String input) {
        if (input == null) return null
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        md.update(salt)
        md.update(input.getBytes(StandardCharsets.UTF_8))
        return Base64.encoder.encodeToString(md.digest())
    }
}
