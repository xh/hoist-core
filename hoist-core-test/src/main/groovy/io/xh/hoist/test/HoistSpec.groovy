/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.user.HoistUser
import spock.lang.Specification

/**
 * Convenient base class for Spock specifications of Hoist application code.
 *
 * Each feature runs with a fresh {@link HoistTestContext} (see {@link HoistTest}), exposed here as
 * {@link #getHoist}, with soft config, users, identity and cluster status all available to
 * services under test.
 *
 * <pre>
 * class WeatherServiceSpec extends HoistSpec {
 *
 *     def 'reads api key from soft config'() {
 *         given:
 *         hoist.configService.set('weatherApiKey', 'abc')
 *         def svc = createService(WeatherService)    // field-initializer createCache() works
 *         loginAs(new TestUser('alice', ['HOIST_ADMIN']))
 *
 *         expect:
 *         svc.user.isHoistAdmin
 *     }
 * }
 * </pre>
 *
 * Specs that extend something else can apply {@link HoistTest} directly instead.
 */
@HoistTest
@CompileStatic
abstract class HoistSpec extends Specification {

    /** The test context for the currently running feature. */
    HoistTestContext getHoist() {
        HoistTestContext.current
    }

    /** Create a service in the test context - see {@link HoistTestContext#createService}. */
    protected <T extends BaseService> T createService(Class<T> clazz, Map<String, ?> props = [:]) {
        hoist.createService(clazz, props)
    }

    /** Log in as the given user - see {@link HoistTestContext#loginAs}. */
    protected HoistUser loginAs(HoistUser user) {
        hoist.loginAs(user)
    }

    /** Log in as the user of the given name - see {@link HoistTestContext#loginAs}. */
    protected HoistUser loginAs(String username) {
        hoist.loginAs(username)
    }
}
