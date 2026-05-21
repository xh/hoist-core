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
 * Centralized BCrypt password hashing for consuming apps' local user accounts. Wraps Spring
 * Security's {@code BCryptPasswordEncoder} so app `User` domain classes don't take a direct
 * dependency on a specific crypto library. Replaces the historical pattern of importing jasypt's
 * {@code BasicPasswordEncryptor} (removed in hoist-core v41 — see the v41 upgrade notes).
 *
 * <p>{@link #matches(String, String)} transparently verifies both new BCrypt hashes and legacy
 * jasypt-default hashes, so pre-upgrade users keep authenticating. Apps can detect legacy hashes
 * via {@link #isLegacyHash(String)} and re-encode post-login to gradually migrate them.
 */
@CompileStatic
final class HoistPasswordEncoder {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder()

    /** Hash a plaintext password using BCrypt. Returns null for null/empty input. */
    static String encode(String rawPassword) {
        return rawPassword ? ENCODER.encode(rawPassword) : null
    }

    /** Verify a plaintext against a BCrypt or legacy jasypt hash. False for null/empty inputs. */
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

    /** True if the hash is in the legacy jasypt format and could be re-encoded post-login. */
    static boolean isLegacyHash(String encodedPassword) {
        if (!encodedPassword) return false
        return !looksLikeBCrypt(encodedPassword) &&
            LegacyJasyptDecrypter.looksLikeLegacyPasswordHash(encodedPassword)
    }

    private static boolean looksLikeBCrypt(String value) {
        return value.length() == 60 && value.startsWith('$2')
    }
}
