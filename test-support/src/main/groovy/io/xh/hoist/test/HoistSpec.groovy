/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import spock.lang.Specification

/**
 * Convenient base class for specs of Hoist code that is not a single Grails artefact under test -
 * e.g. utilities or value objects that log, read soft config or check the current user.
 *
 * Equivalent to `extends Specification implements HoistUnitTest` - see {@link HoistUnitTest}. To
 * test a service or controller, implement the standard Grails trait alongside instead:
 * `extends Specification implements ServiceUnitTest<MyService>, HoistUnitTest`.
 */
abstract class HoistSpec extends Specification implements HoistUnitTest {}
