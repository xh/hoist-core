/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

import io.xh.hoist.json.JSONFormat
import org.hibernate.criterion.Criterion
import org.hibernate.criterion.Restrictions

import static org.hibernate.criterion.MatchMode.ANYWHERE
import static org.hibernate.criterion.MatchMode.END
import static org.hibernate.criterion.MatchMode.START
import static org.hibernate.criterion.Restrictions.ge
import static org.hibernate.criterion.Restrictions.gt
import static org.hibernate.criterion.Restrictions.ilike
import static org.hibernate.criterion.Restrictions.le
import static org.hibernate.criterion.Restrictions.lt
import static org.hibernate.criterion.Restrictions.ne
import static org.hibernate.criterion.Restrictions.not

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

    FieldFilter(String field, String op, Object value) {
        if (!field) {
            throw new IllegalArgumentException('FieldFilter requires a field')
        }

        if (!OPERATORS.contains(op)) {
            throw new IllegalArgumentException("FieldFilter requires valid 'op' value. Operator '$op' not recognized.")
        }

        if (!MULTI_VAL_OPERATORS.contains(op) && value instanceof Collection) {
            throw new IllegalArgumentException("Operator '$op' does not support multiple values. Use a CompoundFilter instead.")
        }

        this.field = field
        this.op = op
        this.value = value instanceof Collection ? new ArrayList(value).unique().sort() : value
    }

    Map formatForJSON() {
        return [
            field: field,
            op: op,
            value: value
        ]
    }


    //---------------------
    // Overrides
    //----------------------
    List<String> getAllFields() {
        return [field]
    }

    Criterion getCriterion() {
        def vals = MULTI_VAL_OPERATORS.contains(op) ?
            (value instanceof List ? value : [value]) :
            null

        switch (op) {
            case '=':
                Criterion c = Restrictions.in(field, vals.findAll { it != null })
                if (vals.contains(null))
                    return or([Restrictions.isNull(field), c])
                return c
            case '!=':
                Criterion c =  and(vals.findAll { it != null }.collect { ne(field, it) })
                if (vals.contains(null))
                    return and([Restrictions.isNotNull(field), c])
                return c
            case '>':
                return gt(field, value)
            case '>=':
                return ge(field, value)
            case '<':
                return lt(field, value)
            case '<=':
                return le(field, value)
            case 'like':
                return or(vals.collect { ilike(field, it as String, ANYWHERE) })
            case 'not like':
                return and(vals.collect { not(ilike(field, it as String, ANYWHERE)) })
            case 'begins':
                return or(vals.collect { ilike(field, it as String, START) })
            case 'ends':
                return or(vals.collect { ilike(field, it as String, END) })
            case 'includes':
            case 'excludes':
                throw new RuntimeException('Unsupported operator for Criteria Filter')
            default:
                throw new RuntimeException("Unknown operator: $op")
        }
    }


    Closure<Boolean> getTestFn() {
        def vals = MULTI_VAL_OPERATORS.contains(op) ?
            (value instanceof List ? value : [value]) :
            null

        switch (op) {
            case '=':
                return {
                    def v = it[field]
                    if (v == '') v = null
                    return vals.any { it == v }
                }
            case '!=':
                return {
                    def v = it[field]
                    if (v == '') v = null
                    return vals.every { it != v }
                }
            case '>':
                return {
                    def v = it[field]
                    return v != null && v > value
                }
            case '>=':
                return {
                    def v = it[field]
                    return v != null && v >= value
                }
            case '<':
                return {
                    def v = it[field]
                    return v != null && v < value
                }
            case '<=':
                return {
                    def v = it[field]
                    return v != null && v <= value
                }
            case 'like':
                def regExps = vals.collect { v -> ~/(?i)$v/ }
                return {
                    def v = it[field]
                    return v != null && regExps.any { re -> re.matcher(v).find() }
                }
            case 'not like':
                def regExps = vals.collect { v -> ~/(?i)$v/ }
                return {
                    def v = it[field]
                    return v != null && !regExps.any { re -> re.matcher(v).find() }
                }
            case 'begins':
                def regExps = vals.collect { v -> ~/(?i)^$v/ }
                return {
                    def v = it[field]
                    return v != null && regExps.any { re -> re.matcher(v).find() }
                }
            case 'ends':
                def regExps = vals.collect { v -> ~/(?i)$v$/ }
                return {
                    def v = it[field]
                    return v != null && regExps.any { re -> re.matcher(v).find() }
                }
            case 'includes':
                return {
                    def v = it[field]
                    return v != null && v.any { vv ->
                        vals.any { it == vv }
                    }
                }
            case 'excludes':
                return {
                    def v = it[field]
                    return v == null || !v.any { vv ->
                        vals.any { it == vv }
                    }
                }
            default:
                throw new RuntimeException("Unknown operator: $op")
        }
    }

    boolean equals(Filter other) {
        if (other === this) return true
        return (
            other instanceof FieldFilter &&
                other.field == field &&
                other.op == op &&
                other.value == value
        )
    }
}
