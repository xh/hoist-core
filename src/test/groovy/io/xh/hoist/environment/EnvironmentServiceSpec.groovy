/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.environment

import grails.testing.services.ServiceUnitTest
import io.xh.hoist.BaseService
import io.xh.hoist.exception.NotAuthenticatedException
import io.xh.hoist.exception.NotFoundException
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.track.TrackLogAdminService
import io.xh.hoist.test.fakes.TestUser
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification

import static io.xh.hoist.environment.EnvironmentService.INVENTORY_TOKEN_HEADER

class EnvironmentServiceSpec extends Specification implements ServiceUnitTest<EnvironmentService>, HoistUnitTest {

    def setup() {
        testConfigService
            .registerTypedConfig('xhInventoryConfig', InventoryConfig)
            .set('xhAppTimeZone', 'America/New_York')
            .set('xhInventoryToken', 'none')
    }

    //------------------
    // Access
    //------------------
    def 'a disabled inventory is reported as not found, even to admins'() {
        given:
        loginAs(new TestUser('admin', ['HOIST_ADMIN_READER']))

        when:
        service.ensureInventoryAccess(request())

        then:
        thrown(NotFoundException)
    }

    def 'an enabled inventory requires a configured token'() {
        given:
        enable()

        when:
        service.ensureInventoryAccess(request((INVENTORY_TOKEN_HEADER): 'anything'))

        then:
        thrown(NotAuthenticatedException)
    }

    def 'token check #desc'() {
        given:
        enable()
        testConfigService.set('xhInventoryToken', 's3cret')

        when:
        def ok = allowed(request(headers))

        then:
        ok == expected

        where:
        desc                         | headers                                    | expected
        'accepts custom header'      | [(INVENTORY_TOKEN_HEADER): 's3cret']       | true
        'accepts bearer header'      | [Authorization: 'Bearer s3cret']           | true
        'accepts lowercase bearer'   | [Authorization: 'bearer s3cret']           | true
        'rejects wrong token'        | [(INVENTORY_TOKEN_HEADER): 's3cre']        | false
        'rejects other auth schemes' | [Authorization: 'Basic s3cret']            | false
        'rejects missing token'      | [:]                                        | false
    }

    def 'admin readers need no token'() {
        given:
        enable()
        loginAs(new TestUser('reader', ['HOIST_ADMIN_READER']))

        expect:
        allowed(request())
    }

    def 'other authenticated users still need a token'() {
        given:
        enable()
        testConfigService.set('xhInventoryToken', 's3cret')
        loginAs(new TestUser('bob'))

        expect:
        !allowed(request())
    }

    def 'no token is needed when requireToken is false'() {
        given:
        enable(requireToken: false)

        expect:
        allowed(request())
    }

    //------------------
    // Report
    //------------------
    def 'report includes metadata, tags, and only non-excluded sections'() {
        given:
        enable(
            excludeSections: EnvironmentService.INVENTORY_SECTIONS - ['app', 'runtime', 'dependencies'],
            tags: [team: 'Risk Tech']
        )

        when:
        def report = service.inventory

        then:
        report.keySet() as List == [
            'inventoryVersion', 'generated', 'generatedBy', 'tags', 'app', 'runtime', 'dependencies'
        ]
        report.inventoryVersion == EnvironmentService.INVENTORY_FORMAT_VERSION
        report.tags == [team: 'Risk Tech']
        report.app.appCode == 'hoist-test'
        report.runtime.appTimeZone == 'America/New_York'
        report.runtime.javaVersion == System.getProperty('java.version')
        report.dependencies.groovy == GroovySystem.version
        report.dependencies.hazelcast
    }

    def 'a section that fails reports its error in place of content'() {
        given: 'no TrackLogAdminService is available to build the usage section'
        enable(excludeSections: EnvironmentService.INVENTORY_SECTIONS - ['usage'])

        when:
        def report = service.inventory

        then:
        report.usage.error
    }

    def 'client section reports declared versions even when observed builds are unavailable'() {
        given: 'no TrackLogAdminService, and optional clientDependencies build metadata'
        enable(excludeSections: EnvironmentService.INVENTORY_SECTIONS - ['client'])
        System.setProperty('info.xh.hoistReactVersion', '76.0.0')
        System.setProperty('info.xh.clientDependencies', '{"@xh/hoist": "^76.0.0", "react": "~19.2.0"}')

        when:
        def client = service.inventory.client

        then:
        client.declared == [hoistReact: '76.0.0', dependencies: ['@xh/hoist': '^76.0.0', react: '~19.2.0']]
        client.observedHoistReact == null
        client.builds.error

        cleanup:
        System.clearProperty('info.xh.hoistReactVersion')
        System.clearProperty('info.xh.clientDependencies')
    }

    def 'client section degrades to nulls without build metadata, and ignores invalid metadata'() {
        given:
        enable(excludeSections: EnvironmentService.INVENTORY_SECTIONS - ['client'])
        if (raw) System.setProperty('info.xh.clientDependencies', raw)

        expect:
        service.inventory.client.declared == [hoistReact: null, dependencies: null]

        cleanup:
        System.clearProperty('info.xh.clientDependencies')

        where:
        raw << [null, 'not json']
    }

    def 'client section summarizes observed hoist-react versions, most recent first'() {
        given:
        enable(excludeSections: EnvironmentService.INVENTORY_SECTIONS - ['client'])
        def trackLogAdmin = Mock(TrackLogAdminService) {
            getClientBuildSummary(30) >> [
                [clientAppCode: 'app', appVersion: '2.1', libraries: [hoistReact: '77.0.0']],
                [clientAppCode: 'admin', appVersion: '2.1', libraries: [hoistReact: '77.0.0']],
                [clientAppCode: 'app', appVersion: '2.0', libraries: null],
                [clientAppCode: 'app', appVersion: '1.9', libraries: [hoistReact: '76.3.1']]
            ]
        }
        service.trackLogAdminService = trackLogAdmin

        expect:
        service.inventory.client.observedHoistReact == ['77.0.0', '76.3.1']
        service.inventory.client.builds.size() == 4
    }

    def 'custom section collects data from InventoryContributors'() {
        given:
        enable(excludeSections: EnvironmentService.INVENTORY_SECTIONS - ['custom'])
        defineService(TestContributorService)

        expect:
        service.inventory.custom.values() as List == [[upstreamVersion: '1.2.3']]
    }

    def 'null config values fall back to safe defaults'() {
        given:
        enable(excludeSections: null, cacheMins: null, usageDays: null)

        when:
        def report = service.inventory

        then:
        report.keySet().containsAll(EnvironmentService.INVENTORY_SECTIONS)
        report.app.appCode == 'hoist-test'
    }

    def 'report is cached until caches are cleared'() {
        given:
        enable(excludeSections: EnvironmentService.INVENTORY_SECTIONS - ['app'])
        def first = service.inventory

        expect:
        service.inventory.is(first)

        when:
        service.clearCaches()

        then:
        !service.inventory.is(first)
    }

    //------------------
    // Helpers
    //------------------
    private void enable(Map opts = [:]) {
        testConfigService.set('xhInventoryConfig', [enabled: true, *: opts])
        service.clearCaches()
    }

    private boolean allowed(MockHttpServletRequest req) {
        try {
            service.ensureInventoryAccess(req)
            return true
        } catch (NotAuthenticatedException ignored) {
            return false
        }
    }

    private static MockHttpServletRequest request(Map<String, String> headers = [:]) {
        def ret = new MockHttpServletRequest('GET', '/xh/inventory')
        headers.each { k, v -> ret.addHeader(k, v) }
        return ret
    }

    static class TestContributorService extends BaseService implements InventoryContributor {
        Map getInventoryData() { [upstreamVersion: '1.2.3'] }
    }
}
