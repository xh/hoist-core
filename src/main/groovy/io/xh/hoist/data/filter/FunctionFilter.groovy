/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

import org.hibernate.criterion.Criterion

/**
 * Filters via a custom function (closure) specified by the developer.
 */
class FunctionFilter extends Filter {

    final private Closure<Boolean> _testFn

    FunctionFilter(Closure<Boolean> testFn) {
        _testFn = testFn
    }

    //---------------------
    // Overrides
    //----------------------
    List<String> getAllFields() {
        return []
    }

    Criterion getCriterion() {
        throw new RuntimeException('Criterion generation not supported for a Function Filter')
    }

    Closure<Boolean> getTestFn() {
        return _testFn
    }

    boolean equals(Filter other) {
        if (other === this) return true
        return (
            other instanceof FunctionFilter &&
                other._testFn == this._testFn
        )
    }
}
