/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.dash

import grails.compiler.GrailsCompileStatic
import io.xh.hoist.BaseService

@GrailsCompileStatic
class DashboardService extends BaseService {

    Object getAll(String appCode) {
        List dashboards = Dashboard.findAllByAppCodeAndUsername(appCode, username)

        if (!dashboards.size()) {
            dashboards.push(get(appCode, -1))
        }

        return dashboards
    }

    Dashboard get(String appCode, Integer id) {
        Dashboard dash = Dashboard.findByAppCodeAndUsernameAndId(appCode, username, id)

        if (!dash) {
            def template = Dashboard.findByAppCodeAndUsername(appCode, 'TEMPLATE')
            if (!template) throw new RuntimeException("Unable to find dashboard $appCode for $username (or TEMPLATE)")

            dash = cloneDashboard(template)
            dash.username = username
            dash.name = '(Default Dashboard)'
            dash.save()
        }

        return dash
    }

    Dashboard save(String appCode, Integer id, String name, String definition) {
        def dash = get(appCode, id)

        dash.name = name
        dash.definition = definition
        dash.save()

        return dash
    }

    void deleteUserDashboard(String appCode, Integer id) {
        Dashboard userDash = Dashboard.findByAppCodeAndUsernameAndId(appCode, username, id)
        userDash?.delete(flush: true)
    }


    //--------------------------------------
    // Implementation
    //--------------------------------------
    private static Dashboard cloneDashboard(Dashboard d) {
        return new Dashboard(
                appCode: d.appCode,
                username: d.username,
                name: '(Default Dashboard)',
                definition: d.definition
        )
    }
}
