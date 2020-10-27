/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONFormat

/**
 * Enum describing available application deployment environments.
 *
 * Hoist itself makes no assumptions or guarantees as to how any given app will behave within a
 * given environment.
 *
 * In particular it is strictly up to the application developer to maintain appropriate controls
 * over how data is read/written and to ensure that such decisions are in-line with how that
 * environment is understood to work within the organization. Several "intermediate" environments
 * are enumerated for convenience that won't have a commonly-understood relationship to production.
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
    DEVELOPMENT('Development'),
    TEST('Test'),
    UAT('UAT'), // User Acceptance Testing - alternate option for beta/staging
    BCP('BCP')  // Business Continuity Planning - aka DR

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
