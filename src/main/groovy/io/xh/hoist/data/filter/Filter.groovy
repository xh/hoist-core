/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

/**
 * Base class for Hoist data package Filters.
 *
 * @see FieldFilter
 * @see CompoundFilter
 * @see FunctionFilter
 */
abstract class Filter {
    /** Return a function that can be used to test a object. */
    abstract Closure<Boolean> getTestFn()

    /** True if the provided other Filter is equivalent to this instance.*/
    abstract boolean equals(Filter other)
}
