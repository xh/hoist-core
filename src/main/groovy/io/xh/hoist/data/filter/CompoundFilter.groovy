/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

import io.xh.hoist.json.JSONFormat
import org.hibernate.criterion.Criterion

/**
 * Combines multiple filters (including other nested CompoundFilters) via an AND or OR operator.
 */
class CompoundFilter extends Filter implements JSONFormat {

    final List<Filter> filters
    final String op

    CompoundFilter(List filters, String op) {
        op = op ? op.toUpperCase() : 'AND'
        if (op != 'AND' && op != 'OR') throw new RuntimeException('CompoundFilter requires "op" value of "AND" or "OR"')
        this.filters = filters.collect { parse(it) }.findAll()
        this.op = op
    }

    Map formatForJSON() {
        return [
            filters: filters,
            op: op
        ]
    }

    //---------------------
    // Overrides
    //----------------------
    List<String> getAllFields() {
        return filters.collectMany { it.allFields }.unique()
    }

    Criterion getCriterion() {
        return op == 'AND' ?  and(filters*.criterion) : or(filters*.criterion)
    }

    Closure<Boolean> getTestFn() {
        if (!filters) return { true }
        def tests = filters*.testFn
        return op == 'AND' ?
            { tests.every { test -> test(it) } } :
            { tests.any { test -> test(it) } }
    }

    boolean equals(Filter other) {
        if (other === this) return true;
        return (
            other instanceof CompoundFilter &&
                other.op == op &&
                other.filters == filters
        )
    }
}
