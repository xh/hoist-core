/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

import io.xh.hoist.json.JSONFormat

/**
 * Combines multiple filters (including other nested CompoundFilters) via an AND or OR operator.
 */
class CompoundFilter extends Filter implements JSONFormat {

    final List<Filter> filters
    final String op

    /** Constructor - not typically called by apps - create via {@link Utils#parseFilter} instead. */
    CompoundFilter(List filters, String op) {
        op = op ? op.toUpperCase() : 'AND'
        if (op != 'AND' && op != 'OR') throw new IllegalArgumentException('CompoundFilter requires "op" value of "AND" or "OR"')
        this.filters = filters.collect { Utils.parseFilter(it) }.findAll { it != null }
        this.op = op
    }

    Map formatForJSON() {
        return [
            filters: filters,
            op: op
        ]
    }

    //-----------------
    // Overrides
    //-----------------
    Closure<Boolean> getTestFn() {
        if (!filters) return { true }
        def tests = filters.collect { it.getTestFn() };
        return op == 'AND' ?
            { tests.every { test -> test(it) } } :
            { tests.any { test -> test(it) } }
    }

    boolean equals(Filter other) {
        if (other === this) return true;
        return (
            other instanceof CompoundFilter &&
                other.op == this.op &&
                other.filters == this.filters
        )
    }
}
