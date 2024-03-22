/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

/**
 * Filters via a custom function (closure) specified by the developer.
 */
class FunctionFilter extends Filter {

    final private Closure<Boolean> _testFn

    /** Constructor - not typically called by apps - create via {@link Utils#parseFilter} instead. */
    FunctionFilter(Closure<Boolean> testFn) {
        if (!testFn) throw new IllegalArgumentException('FunctionFilter requires a `testFn`')
        this._testFn = testFn
    }

    Closure<Boolean> getTestFn() {
        return this._testFn
    }

    boolean equals(Filter other) {
        if (other === this) return true
        return (
            other instanceof FunctionFilter &&
                other._testFn == this._testFn
        )
    }
}
