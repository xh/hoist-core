/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import org.slf4j.Marker
import org.slf4j.Logger

/**
 * A Marker representing an enhanced, meta-data preserving log message produced by
 * LogSupport.  This marker should be interpreted by any converter used in a Hoist
 * Application.
 */
class LogSupportMarker implements Marker {

    /**
     * List of messages to be logged.  May contain Maps, or sublists, in the case of structured
     * data.  It is the responsibility of the converter to serialize these appropriately.
     */
    List messages
    Logger logger

    LogSupportMarker(Logger logger, List messages) {
        this.logger = logger
        this.messages = messages
    }

    String getName() {
        return 'LogSupportMarker'
    }

    //-----------------------------------------------------
    // Degenerate implementation, no children or references
    //------------------------------------------------------
    void add(Marker reference) {}
    boolean remove(Marker reference) {return false}
    boolean hasChildren() {return false}
    boolean hasReferences() {return false}
    Iterator<Marker> iterator() {return null}
    boolean contains(Marker other) {return false}
    boolean contains(String name) {return false}
}
