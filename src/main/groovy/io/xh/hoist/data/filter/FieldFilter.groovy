/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

import io.xh.hoist.json.JSONFormat

/**
 * Filters by comparing the value of a given field to one or more given candidate values using a given operator.
 * Note that the comparison operators `[<,<=,>,>=]` always return false for null values (as with Excel filtering).
 */
class FieldFilter extends Filter implements JSONFormat {

    final String field
    final String op
    final Object value

    /** All available operators. */
    static OPERATORS = ['=', '!=', '>', '>=', '<', '<=', 'like', 'not like', 'begins', 'ends', 'includes', 'excludes']
    /** All operators that support testing multiple candidate `value`s (where this.value *can be* a collection). */
    static MULTI_VAL_OPERATORS = ['=', '!=', 'like', 'not like', 'begins', 'ends', 'includes', 'excludes']

    /** Constructor - not typically called by apps - create via {@link Utils#parseFilter} instead. */
    FieldFilter(String field, String op, Object value) {
        if (!field) {
            throw new IllegalArgumentException('FieldFilter requires a field')
        }

        if (value == null) {
            throw new IllegalArgumentException('FieldFilter requires a value')
        }

        if (!OPERATORS.contains(op)) {
            throw new IllegalArgumentException("FieldFilter requires valid 'op' value. Operator '${op}' not recognized.")
        }

        if (!MULTI_VAL_OPERATORS.contains(op) && value instanceof Collection) {
            throw new IllegalArgumentException("Operator '${op}' does not support multiple values. Use a CompoundFilter instead.")
        }

        this.field = field
        this.op = op

        if (value instanceof Collection) {
            this.value = new ArrayList(value).unique().sort()
        } else {
            this.value = value
        }
    }

    Map formatForJSON() {
        return [
            field: field,
            op: op,
            value: value
        ]
    }

    Closure<Boolean> getTestFn() {
        def filterVal = this.value

        if (MULTI_VAL_OPERATORS.contains(op)) {
            filterVal = filterVal instanceof List ? filterVal : [filterVal]
        }

        switch (op) {
            case '=':
                return {
                    def v = it[field]
                    if (v == '') v = null
                    return filterVal.any { it == v }
                }
            case '!=':
                return {
                    def v = it[field]
                    if (v == '') v = null
                    return filterVal.every { it != v }
                }
            case '>':
                return {
                    def v = it[field]
                    return v != null && v > filterVal
                }
            case '>=':
                return {
                    def v = it[field]
                    return v != null && v >= filterVal
                }
            case '<':
                return {
                    def v = it[field]
                    return v != null && v < filterVal
                }
            case '<=':
                return {
                    def v = it[field]
                    return v != null && v <= filterVal
                }
            case 'like':
                def regExps = filterVal.collect { v -> ~/(?i)$v/ }
                return {
                    def v = it[field]
                    return v != null && regExps.any { re -> re.matcher(v).find() }
                }
            case 'not like':
                def regExps = filterVal.collect { v -> ~/(?i)$v/ }
                return {
                    def v = it[field]
                    return v != null && !regExps.any { re -> re.matcher(v).find() }
                }
            case 'begins':
                def regExps = filterVal.collect { v -> ~/(?i)^$v/ }
                return {
                    def v = it[field]
                    return v != null && regExps.any { re -> re.matcher(v).find() }
                }
            case 'ends':
                def regExps = filterVal.collect { v -> ~/(?i)$v$/ }
                return {
                    def v = it[field]
                    return v != null && regExps.any { re -> re.matcher(v).find() }
                }
            case 'includes':
                return {
                    def v = it[field]
                    return v != null && v.any { vv ->
                        filterVal.any { it == vv }
                    }
                }
            case 'excludes':
                return {
                    def v = it[field]
                    return v == null || !v.any { vv ->
                        filterVal.any { it == vv }
                    }
                }
            default:
                throw new RuntimeException("Unknown operator: ${op}")
        }
    }

    boolean equals(Filter other) {
        if (other === this) return true
        return (
            other instanceof FieldFilter &&
                other.field == this.field &&
                other.op == this.op &&
                other.value == this.value
        )
    }
}
