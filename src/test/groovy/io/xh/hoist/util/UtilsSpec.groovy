/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import io.xh.hoist.test.HoistSpec
import spock.lang.Unroll

class UtilsSpec extends HoistSpec {

    def cleanup() {
        // Reset the private cache of sensitive terms, so it is re-read from config by other specs
        Utils.terms = null
    }

    @Unroll
    def 'parseBooleanStrict(#input) -> #expected'() {
        expect:
        Utils.parseBooleanStrict(input) == expected

        where:
        input   | expected
        'true'  | true
        'TRUE'  | true
        'False' | false
        'false' | false
    }

    @Unroll
    def 'parseBooleanStrict rejects #input'() {
        when:
        Utils.parseBooleanStrict(input)

        then:
        def e = thrown(RuntimeException)
        e.message == 'Unable to parse boolean value'

        where:
        input << [' true', 'yes', '1', '', null]
    }

    def 'createCustomOrDefault falls back to default for unknown class'() {
        expect:
        Utils.createCustomOrDefault('no.such.Clazz', ArrayList) instanceof ArrayList
    }

    def 'createCustomOrDefault instantiates an existing class'() {
        expect:
        Utils.createCustomOrDefault('java.util.LinkedList', List) instanceof LinkedList
    }

    def 'createCustomOrDefault propagates failure for a class without a no-arg constructor'() {
        when:
        Utils.createCustomOrDefault('java.lang.Integer', Number)

        then:
        thrown(NoSuchMethodException)
    }

    def 'withDelegate sets the closure delegate'() {
        given:
        def seen

        when:
        Utils.withDelegate([a: 1]) { seen = a }

        then:
        seen == 1
    }

    @Unroll
    def 'isJSON(#input) -> #expected'() {
        expect:
        Utils.isJSON(input) == expected

        where:
        input       | expected
        '{"a":1}'   | true
        '[1,2]'     | true
        '{a'        | false
    }

    @Unroll
    def 'isSensitiveParamName(#name) -> #expected'() {
        given:
        Utils.terms = ['password', 'passwrd', 'pwd', 'secret', 'tkn', 'token']

        expect:
        Utils.isSensitiveParamName(name) == expected

        where:
        name           | expected
        'password'     | true
        'userPwd'      | true
        'apiToken'     | true
        'TKN'          | true
        'clientSecret' | true
        'username'     | false
        'tokenizer'    | true
    }

    def 'asSanitizedJSON redacts sensitive keys at any map depth'() {
        given:
        Utils.terms = ['password', 'token']

        when:
        def ret = Utils.asSanitizedJSON([user: 'bob', password: 'x', nested: [apiToken: 'y', ok: 1]])

        then:
        ret == [user: 'bob', password: '******', nested: [apiToken: '******', ok: 1]]
    }

    def 'asSanitizedJSON redacts sensitive keys inside lists'() {
        given:
        Utils.terms = ['password']

        expect:
        Utils.asSanitizedJSON([users: [[password: 'x']]]) == [users: [[password: '******']]]
    }

    def 'getCurrentRequest is null outside of a request'() {
        expect:
        Utils.currentRequest == null
    }
}
