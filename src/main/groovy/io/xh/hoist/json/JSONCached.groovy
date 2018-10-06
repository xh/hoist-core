/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */


package io.xh.hoist.json

import groovy.transform.CompileStatic

@CompileStatic
class JSONCached {

    final String cached

    JSONCached(Object o) {
        cached = new JSON(o).toString()
    }

    String toString() {cached}
    
}
