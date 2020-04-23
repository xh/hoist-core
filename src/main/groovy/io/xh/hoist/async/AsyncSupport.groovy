/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.async

import grails.async.Promise
import grails.async.Promises
import groovy.transform.CompileStatic
import io.xh.hoist.util.Utils

@CompileStatic
trait AsyncSupport {

    /**
     * Create a grails.async.Promise.
     */
    static Promise asyncTask(Closure c) {
        Promises.task(c)
    }
}
