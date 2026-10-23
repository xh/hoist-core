/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test.fakes

import groovy.transform.CompileStatic
import io.xh.hoist.track.TrackService

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Recording {@link TrackService} for tests. Entries passed to `track()` or `trackAll()` are
 * collected in {@link #getTracked} rather than persisted, logged or published, so a spec can
 * assert that code under test recorded the activity it should have:
 *
 * <pre>
 * service.deleteWidget(id)
 *
 * expect:
 * testTrackService.lastTracked.category == 'Audit'
 * testTrackService.lastTracked.msg.contains('Deleted widget')
 * </pre>
 *
 * Each recorded entry is the Map of named arguments as passed, with null values removed.
 * Registered by {@link io.xh.hoist.test.HoistUnitTest} as the application's `trackService` bean
 * and cleared before each feature.
 */
@CompileStatic
class TestTrackService extends TrackService {

    private final List<Map> entries = new CopyOnWriteArrayList<Map>()

    /** All entries tracked so far, oldest first. */
    List<Map> getTracked() {
        entries.asImmutable()
    }

    /** The most recently tracked entry, or null if none. */
    Map getLastTracked() {
        entries ? entries.last() : null
    }

    /** Entries tracked with the given category. */
    List<Map> tracked(String category) {
        entries.findAll { it.category == category }
    }

    /** Forget all tracked entries. */
    void clear() {
        entries.clear()
    }

    @Override
    void trackAll(Collection<Map> entries) {
        entries.each { Map entry -> this.entries << entry.findAll { k, v -> v != null } }
    }

    /** Tracking is always on, regardless of `xhActivityTrackingConfig`. */
    @Override
    boolean getEnabled() {
        true
    }
}
