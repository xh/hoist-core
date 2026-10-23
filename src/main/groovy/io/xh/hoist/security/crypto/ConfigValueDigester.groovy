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

/**
 * Deterministic SHA-256 digest of `AppConfig` `pwd` values for the Admin Console's Config Diff
 * workflow. Identical inputs produce identical output on any JVM running this version of
 * hoist-core, so two environments' configs can be compared by JSON-level diff without exposing
 * plaintext. Visibility is gated to `HOIST_ADMIN_READER` — the same trust boundary as the
 * plaintext read path — so no salt is required.
 */
@CompileStatic
final class ConfigValueDigester {

    /** Returns a Base64-encoded SHA-256 digest of the given input, or null for null input. */
    String digest(String input) {
        if (input == null) return null
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        return Base64.encoder.encodeToString(md.digest(input.getBytes(StandardCharsets.UTF_8)))
    }
}
