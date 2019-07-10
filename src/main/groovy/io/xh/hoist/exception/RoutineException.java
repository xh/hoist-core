package io.xh.hoist.exception;

/**
 * Marker interface for an exception that is an expected part of the
 * application flow.
 *
 * NotAuthorizedException, and DataUnavailableException are important examples
 * of this interface.
 *
 * Hoist will log exceptions of this type minimally -- they primarily exists
 * to efficiently provide out-of-band responses from the server to the client.
 */
public interface RoutineException {}
