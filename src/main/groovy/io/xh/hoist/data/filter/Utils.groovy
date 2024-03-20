/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

import groovy.transform.CompileStatic

@CompileStatic
class Utils {

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
    static Filter parseFilter(spec) {
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

        throw new IllegalArgumentException("Unable to identify filter type: ${spec}")
    }

    /**
     * Extract a list of fields used by filter
     */
    static List getFilterFields(Filter filter) {
        if (filter instanceof FunctionFilter) return []
        if (filter instanceof FieldFilter) return [filter.field]
        if (filter instanceof CompoundFilter) {
            return filter.filters.collect { getFilterFields(it) }.flatten()
        }
    }

    /**
     * Create an SQL predicate string from a Filter instance.
     * @param filters
     * @param symbol
     * @return
     */
    static String createPredicateFromFilters(Filter filters, String tableAlias) {
        switch (filters) {
            case FieldFilter:
                return processFieldFilter(filters, filters.op, tableAlias)
            case CompoundFilter:
                return processCompoundFilter(filters.filters, filters.op, tableAlias)
            case FunctionFilter:
                throw new RuntimeException("FunctionFilter not supported in createPredicateFromFilters")
            case null:
                return "1=1"
        }
    }

    //-------------------------
    // Implementation
    //-------------------------
    private static String processCompoundFilter(List<Filter> filters, String op, String tableAlias) {
        def subFilters = filters.collect { createPredicateFromFilters(it, tableAlias)}
        return "(" + subFilters.join(" $op ") + ")"
    }

    private static String processFieldFilter(FieldFilter filter, String op, String tableAlias) {
        if (filter.value instanceof Collection) {
            def subFilters = filter.value.collect {
                createPredicateFromFilters(new FieldFilter(filter.field, op, it), tableAlias)
            }
            switch (op) {
                case "=":
                case "like":
                case "begins":
                case "ends":
                    return "(" + subFilters.join(" OR ") + ")"
                case "!=":
                case "not like":
                    return "(" + subFilters.join(" AND ") + ")"
            }
        }
        switch (op) {
            case "=":
            case "!=":
                return "${tableAlias}.${filter.field} $op '${filter.value}'"
            case "like":
            case "not like":
                return "${tableAlias}.${filter.field} $op '%${filter.value}%'"
            case "begins":
                return "${tableAlias}.${filter.field} like '${filter.value}%'"
            case "ends":
                return "${tableAlias}.${filter.field} like '%${filter.value}'"
            default:
                throw new RuntimeException("Unsupported operator: $op")
        }

    }
}
