/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cluster

import io.xh.hoist.exception.InstanceNotAvailableException
import io.xh.hoist.test.HoistSpec
import io.xh.hoist.util.Utils
import org.objenesis.ObjenesisStd

class ClusterTaskSpec extends HoistSpec {

    def cleanup() {
        Utils.setAppContext(applicationContext)
    }

    def 'call returns routine not-available result when app context not yet installed'() {
        given: 'a task as received on an instance that has joined the cluster but is still starting'
        // Instantiate without constructor, as Kryo does when deserializing on the target instance.
        def task = new ObjenesisStd().newInstance(ClusterTask)
        Utils.setAppContext(null)

        when:
        def result = task.call()

        then:
        result.value == null
        with(result.exception) {
            causeClassName == InstanceNotAvailableException.name
            causeMessage == ClusterService.INITIALIZING_MESSAGE
            causeStatusCode == 400
        }
    }
}
