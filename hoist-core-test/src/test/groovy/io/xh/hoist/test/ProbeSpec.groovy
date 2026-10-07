package io.xh.hoist.test
import spock.lang.Specification
class ProbeSpec extends Specification { def 'ser'() { expect: Probe.ser([a: 1]) == '{"a":1}' } }
