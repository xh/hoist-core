/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

import org.hibernate.criterion.Conjunction
import org.hibernate.criterion.Criterion
import org.hibernate.criterion.Disjunction

import static org.hibernate.criterion.Restrictions.and
import static org.hibernate.criterion.Restrictions.or

/**
 * Base class for Hoist data package Filters.
 *
 * @see FieldFilter
 * @see CompoundFilter
 * @see FunctionFilter
 */
abstract class Filter {
    /** True if the provided other Filter is equivalent to this instance.*/
    abstract boolean equals(Filter other)

    /** Return a Hibernate Criterion representing this filter. */
    abstract Criterion getCriterion()

    /** Return a function that can be used to test a object. */
    abstract Closure<Boolean> getTestFn()

    /** Get all fields used by a filter, or its sub-filters */
    abstract List<String> getAllFields()


    /**
     * Parse/create a Filter from a map or closure, or a collection of the same.
     *
     * @param spec - one or more filters or specs to create one - can be:
     *      * A falsey value, returned as null. A null filter represents no filter at all,
     *        or the equivalent of a filter that always passes every record.
     *      * An existing Filter instance, returned directly as-is.
     *      * A raw Closure, returned as a {@link FunctionFilter}.
     *      * A Collection of nested specs, returned as a {@link CompoundFilter} with a default 'AND' operator.
     *      * A map, returned as an appropriate concrete Filter subclass based on its properties.
     */
    static Filter parse(Object spec) {
        // Degenerate cases
        if (!spec) return null
        if (spec instanceof Filter) return spec

        // Normalize special forms
        Map specMap
        if (spec instanceof Closure) {
            specMap = [testFn: spec]
        } else if (spec instanceof Collection) {
            specMap = [filters: spec]
        } else {
            specMap = spec as Map
        }

        // Branch on properties
        if (specMap.field) {
            return new FieldFilter(
                specMap.field as String,
                specMap.op as String,
                specMap.value
            )
        }

        if (specMap.testFn) {
            return new FunctionFilter(
                specMap.testFn as Closure
            )
        }

        if (specMap.filters) {
            def ret = new CompoundFilter(
                specMap.filters as List,
                specMap.op as String
            )
            switch (ret.filters.size()) {
                case 0: return null
                case 1: return ret.filters[0]
                default: return ret
            }
        }

        throw new RuntimeException("Unable to identify filter type: $spec")
    }


    //-------------------------
    // Implementation
    //-------------------------
    protected static Criterion and(List<Criterion> criteria) {
        if (criteria.size() == 1) return criteria[0]

        def conjunction = new Conjunction()
        criteria.each { conjunction.add(it) }
        return conjunction
    }
    protected static Criterion or(List<Criterion> criteria) {
        if (criteria.size() == 1) return criteria[0]

        def disjunction = new Disjunction()
        criteria.each { disjunction.add(it) }
        return disjunction
    }
}
