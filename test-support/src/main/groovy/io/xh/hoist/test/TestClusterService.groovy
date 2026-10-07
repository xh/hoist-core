/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import io.xh.hoist.cluster.ClusterService

/**
 * {@link ClusterService} for tests that reports a configurable primary/secondary status without
 * any Hazelcast cluster membership. Registered by {@link HoistUnitTest} as the application's
 * `clusterService` bean, where it is the primary instance by default - so that `primaryOnly`
 * timers and `isPrimary` checks in services under test behave as they would on a single instance.
 */
class TestClusterService extends ClusterService {

    /** Is this (simulated) instance the primary? Set to false to test secondary-instance behavior. */
    boolean primaryInstance = true

    @Override
    boolean getIsPrimary() {
        primaryInstance
    }
}
