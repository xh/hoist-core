/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security.crypto

import spock.lang.Specification

class SaltedSha256DigesterSpec extends Specification {

    def 'same instance produces stable output for same input'() {
        given:
        def digester = new SaltedSha256Digester()

        expect:
        digester.digest('hello') == digester.digest('hello')
        digester.digest('') == digester.digest('')
    }

    def 'different inputs hash differently within an instance'() {
        given:
        def digester = new SaltedSha256Digester()

        expect:
        digester.digest('hello') != digester.digest('world')
    }

    def 'different instances produce different output for same input (random salt)'() {
        given:
        def a = new SaltedSha256Digester()
        def b = new SaltedSha256Digester()

        expect:
        a.digest('hello') != b.digest('hello')
    }

    def 'null input returns null'() {
        expect:
        new SaltedSha256Digester().digest(null) == null
    }

    def 'digest output is non-empty base64'() {
        when:
        def out = new SaltedSha256Digester().digest('hello')

        then:
        out
        out.length() > 0
        Base64.decoder.decode(out).length == 32 // SHA-256
    }
}
