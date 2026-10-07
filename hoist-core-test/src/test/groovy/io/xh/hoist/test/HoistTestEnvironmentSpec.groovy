/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import io.xh.hoist.AppEnvironment
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.util.Utils
import spock.lang.Specification

class HoistTestEnvironmentSpec extends Specification {

    def 'global extension has configured test system properties before any spec runs'() {
        expect:
        System.getProperty('io.xh.hoist.environment') == 'Test'
        System.getProperty('info.xh.appCode')
        System.getProperty('info.app.version')
        new File(System.getProperty('io.xh.hoist.instanceConfigFile')).text.contains('multiInstanceEnabled')
    }

    def 'framework statics resolve without an application'() {
        expect:
        Utils.appEnvironment == AppEnvironment.TEST
        !ClusterService.multiInstanceEnabled
        ClusterService.clusterName.startsWith(Utils.appCode)
    }

    def 'ensureInitialized is idempotent and does not replace existing values'() {
        when:
        HoistTestEnvironment.ensureInitialized()
        HoistTestEnvironment.ensureInitialized()

        then:
        System.getProperty('io.xh.hoist.environment') == 'Test'
        HoistTestEnvironment.instanceConfigFile.is(HoistTestEnvironment.instanceConfigFile)
    }
}
