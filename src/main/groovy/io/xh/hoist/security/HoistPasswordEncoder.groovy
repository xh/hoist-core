/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.security.crypto.LegacyJasyptDecrypter
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/**
 * Centralized password hashing for consuming apps' local user accounts. Wraps Spring Security's
 * {@code BCryptPasswordEncoder} so app-level User domain classes don't need to take a direct
 * dependency on a specific crypto library.
 *
 * <p>This replaces the historical pattern of importing jasypt's {@code BasicPasswordEncryptor}
 * directly in each app's User domain class. The jasypt dependency was removed in hoist-core
 * v41.0 — it is end-of-life (last released 2014) and breaks at runtime on JDK 21+ under Spring
 * Boot's launcher classloader due to a Unicode-normalization reflection bug. See the v41
 * upgrade notes for migration details.
 *
 * <h3>Typical usage in a User domain class</h3>
 * <pre>
 * import io.xh.hoist.security.HoistPasswordEncoder
 *
 * class User {
 *     String username
 *     String password
 *
 *     def beforeInsert() { encodePassword() }
 *     def beforeUpdate() { if (isDirty('password')) encodePassword() }
 *
 *     boolean checkPassword(String plain) {
 *         HoistPasswordEncoder.matches(plain, password)
 *     }
 *
 *     private void encodePassword() {
 *         password = HoistPasswordEncoder.encode(password)
 *     }
 * }
 * </pre>
 *
 * <h3>Migrating legacy users</h3>
 *
 * {@link #matches(String, String)} transparently verifies passwords stored in either the new
 * BCrypt format or the legacy jasypt format (8-byte-salt MD5, the default produced by
 * {@code BasicPasswordEncryptor}). This allows existing users to log in successfully against a
 * database populated under jasypt. App code is encouraged to detect legacy hashes after a
 * successful match and re-save the user with a freshly {@link #encode encoded} password — see
 * {@link #isLegacyHash(String)} and the v41 upgrade notes for a complete pattern.
 *
 * <p>Once all users have logged in at least once after the upgrade, the legacy path becomes
 * dead code and may be removed in a future release.
 */
@CompileStatic
final class HoistPasswordEncoder {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder()

    /** Hash a plaintext password using BCrypt. Returns null for null/empty input. */
    static String encode(String rawPassword) {
        return rawPassword ? ENCODER.encode(rawPassword) : null
    }

    /**
     * Verify a plaintext password against an encoded hash. Supports both the current BCrypt
     * format and the legacy jasypt-default format (`BasicPasswordEncryptor`). Returns false
     * for null/empty inputs.
     */
    static boolean matches(String rawPassword, String encodedPassword) {
        if (!rawPassword || !encodedPassword) return false
        if (looksLikeBCrypt(encodedPassword)) {
            try {
                return ENCODER.matches(rawPassword, encodedPassword)
            } catch (IllegalArgumentException ignored) {
                return false
            }
        }
        return LegacyJasyptDecrypter.matchesLegacyPasswordHash(rawPassword, encodedPassword)
    }

    /**
     * True if the given encoded hash is in the legacy jasypt format and should be re-encoded
     * with {@link #encode} on next opportunity (typically post-login). Callers can use this to
     * implement migrate-on-login behavior.
     */
    static boolean isLegacyHash(String encodedPassword) {
        if (!encodedPassword) return false
        return !looksLikeBCrypt(encodedPassword) &&
            LegacyJasyptDecrypter.looksLikeLegacyPasswordHash(encodedPassword)
    }

    private static boolean looksLikeBCrypt(String value) {
        return value.length() == 60 && value.startsWith('$2')
    }
}
