/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cache

import groovy.transform.CompileStatic

@CompileStatic
class Entry<V> {

    final V value
    final Long dateEntered = System.currentTimeMillis()

    Entry(Object value) {
        this.value = (V) value
    }
    
}
