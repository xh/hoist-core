/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import groovy.transform.CompileStatic
import io.opentelemetry.api.trace.Span
import org.slf4j.Marker
import org.slf4j.Logger

import static io.xh.hoist.util.Utils.getIdentityService

/**
 * A Marker representing an enhanced, meta-data preserving log message produced by
 * LogSupport.  This marker should be interpreted by any converter used in a Hoist
 * Application.
 */
@CompileStatic
class LogSupportMarker implements Marker {

    /**
     * List of messages to be logged.  May contain Maps, or sublists, in the case of structured
     * data.  It is the responsibility of the converter to serialize these appropriately.
     */
    final List messages

    /** Logger sending this message. */
    final Logger logger

    /** Authenticated user at time of logging, or null. */
    final String username

    /** Active trace ID at time of logging, or null. */
    final String traceId

    /** Active span ID at time of logging, or null. */
    final String spanId

    LogSupportMarker(Logger logger, Object messages) {
        this.logger = logger
        this.messages = Arrays.asList(messages).flatten()
        this.username = identityService?.username
        def spanContext = Span.current()?.spanContext
        this.traceId = spanContext?.valid ? spanContext.traceId : null
        this.spanId = spanContext?.valid ? spanContext.spanId : null
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
