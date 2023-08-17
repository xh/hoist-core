/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

/**
 * General concrete exception for an exception that is an expected part of the
 * application flow.
 *
 * Hoist may log this exception minimally or not at all. It primarily exist to provide
 * out-of-band responses from the server to the client.
 *
 * @see RoutineException
 */
class RoutineRuntimeException extends RuntimeException implements RoutineException {
    RoutineRuntimeException(String s) {
        super(s)
    }
}
