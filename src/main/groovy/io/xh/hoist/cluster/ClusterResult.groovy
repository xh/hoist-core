package io.xh.hoist.cluster

/**
 * DTO object used for transferring the results of a remote execution from one cluster instance
 * to another.
 *
 * Depending on the call, value may be in serialized Json form. Note that in that case an intended
 * null return will be represented as the string "null"; In contrast a void return will be
 * represented as an actual java null pointer.
 */
class ClusterResult {
    Object value
    ClusterTaskException exception
}