/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.util

import io.xh.hoist.json.JSONFormat

/**
 * Immutable holder for the result of an operation that can either succeed with a value or fail
 * with a String description of the error. Intended for entries within batch results, where
 * per-entry failures are reported as data alongside successful entries rather than thrown -
 * see {@link io.xh.hoist.directory.DirectoryService} for a primary use.
 *
 * <p>Serializes to JSON as the bare wrapped value on success, or the bare error String on
 * failure, allowing instances to stand in directly for the raw value in API responses.
 */
class ErrorOr<T> implements JSONFormat {

    /** The wrapped result value - null on failure. */
    public final T value

    /** Description of the failure - null on success. */
    public final String error

    /** Create a successful result wrapping the given value. */
    static <T> ErrorOr<T> of(T value) {
        new ErrorOr<T>(value, null)
    }

    /** Create a failed result with a description of the error. */
    static <T> ErrorOr<T> error(String error) {
        new ErrorOr<T>(null, error ?: 'Unknown error')
    }

    boolean isSuccess() {
        error == null
    }

    Object formatForJSON() {
        success ? value : error
    }

    private ErrorOr(T value, String error) {
        this.value = value
        this.error = error
    }
}
