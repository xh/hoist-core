/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONFormat

/**
 * Enum describing available application deployment environments.
 *
 * Note this is distinct from the built-in Grails notion of Environments as there may be multiple
 * non-production app instances (e.g., Development, Beta) that all run in Grails "production" mode
 * on their respective servers.
 */
@CompileStatic
enum AppEnvironment implements JSONFormat {
    PRODUCTION('Production'),
    BETA('Beta'),
    STAGING('Staging'),
    DEVELOPMENT('Development')

    final String displayName

    private AppEnvironment(String displayName) {
        this.displayName = displayName
    }

    String toString() {displayName}

    String formatForJSON() {toString()}

    static AppEnvironment parse(String str) {
        return values().find {it.displayName == str}
    }
    
}
