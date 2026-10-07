package io.xh.hoist

import spock.lang.Specification

class AppEnvironmentSpec extends Specification {

    def 'parse matches display name case-insensitively'() {
        expect:
        AppEnvironment.parse(input) == expected

        where:
        input        | expected
        'Production' | AppEnvironment.PRODUCTION
        'production' | AppEnvironment.PRODUCTION
        'BETA'       | AppEnvironment.BETA
        'prod'       | null
        null         | null
    }
}
