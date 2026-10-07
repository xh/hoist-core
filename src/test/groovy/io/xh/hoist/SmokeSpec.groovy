package io.xh.hoist
import spock.lang.Specification
class SmokeSpec extends Specification {
    def 'works'() { expect: AppEnvironment.parse('production') == AppEnvironment.PRODUCTION }
}
