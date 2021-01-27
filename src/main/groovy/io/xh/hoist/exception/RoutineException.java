/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception;

/**
 * Marker interface for an exception that is an expected part of the application flow.
 *
 * Hoist may log exceptions of this type minimally or not at all. They primarily exist to provide
 * out-of-band responses from the server to the client. They specifically do NOT indicate unexpected
 * or unhandled bugs in server-side code or processing that should trigger alerts or require fixing.
 *
 * For example usages of this interface within Hoist:
 * @see RoutineRuntimeException
 * @see NotAuthorizedException
 * @see DataNotAvailableException
 */
public interface RoutineException {}
