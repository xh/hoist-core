/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cluster

class InstanceNotFoundException extends RuntimeException {
    InstanceNotFoundException(String s) {
        super(s)
    }
}