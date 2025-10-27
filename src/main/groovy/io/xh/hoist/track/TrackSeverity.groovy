package io.xh.hoist.track

/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

enum TrackSeverity {
    DEBUG, INFO, WARN, ERROR

    static TrackSeverity parse(String s) {
        if (!s) return INFO
        try {
            return TrackSeverity.valueOf(s.toUpperCase().trim())
        } catch (IllegalArgumentException ex) {
            return INFO
        }
    }
}