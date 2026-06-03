/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security.crypto

import spock.lang.Specification

class ConfigValueDigesterSpec extends Specification {

    def 'digest is deterministic across distinct instances (process-stable)'() {
        // This is the contract that makes admin Config Diff work across two environments — two
        // hoist-core JVMs computing the digest of the same plaintext must produce identical output.
        given:
        def a = new ConfigValueDigester()
        def b = new ConfigValueDigester()

        expect:
        a.digest('hello') == b.digest('hello')
        a.digest('') == b.digest('')
        a.digest('a-much-longer-value-that-still-digests-fine') ==
            b.digest('a-much-longer-value-that-still-digests-fine')
    }

    def 'digest matches the published SHA-256 of the input'() {
        // Pinning a known SHA-256 output guards against accidental algorithm changes that would
        // silently break cross-version Config Diff for apps mid-upgrade.
        expect:
        new ConfigValueDigester().digest('hello') ==
            'LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=' // sha256('hello') base64
    }

    def 'different inputs hash differently'() {
        given:
        def digester = new ConfigValueDigester()

        expect:
        digester.digest('hello') != digester.digest('world')
    }

    def 'null input returns null'() {
        expect:
        new ConfigValueDigester().digest(null) == null
    }

    def 'digest output is base64-encoded 32 bytes'() {
        when:
        def out = new ConfigValueDigester().digest('hello')

        then:
        out
        Base64.decoder.decode(out).length == 32 // SHA-256 output size
    }
}
