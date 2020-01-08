/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.dash

import grails.compiler.GrailsCompileStatic
import io.xh.hoist.BaseService

@GrailsCompileStatic
class DashboardService extends BaseService {

    Dashboard get(String appCode) {
        // Lookup users copy, OR template
        // Currently we return the template but we could eagerly save user copy
        Dashboard dash = Dashboard.findByAppCodeAndUsername(appCode, username) ?:
                         Dashboard.findByAppCodeAndUsername(appCode, 'TEMPLATE')

        if (!dash) throw new RuntimeException("Unable to find dashboard $appCode for $username (or TEMPLATE)")

        return dash
    }

    Dashboard save(String appCode, String definition) {
        def username = username,
            dash = get(appCode)
        if (dash.username != username) {
            dash = cloneDashboard(dash)
            dash.username = username
        }
        dash.definition = definition
        dash.save()
        return dash
    }

    void deleteUserInstance(String appCode) {
        Dashboard userDash = Dashboard.findByAppCodeAndUsername(appCode, username)
        userDash?.delete(flush: true)
    }


    //------------------------
    // Implementation
    //------------------------
    private static Dashboard cloneDashboard(Dashboard d) {
        return new Dashboard(
                appCode: d.appCode,
                username: d.username,
                definition: d.definition
        )
    }
    
}
