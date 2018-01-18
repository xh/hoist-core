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

    List getAll(String appCode) {
        List<Dashboard> dashboards = Dashboard.findAllByAppCodeAndUsername(appCode, username)

        if (!dashboards.size()) {
            dashboards.push(createFromTemplate(appCode))
        }

        return dashboards
    }

    Dashboard create(String appCode, String name, String definition) {
        Dashboard dash = new Dashboard(
                appCode: appCode,
                name: name,
                username: username,
                definition: definition,
                dateCreated: new Date()
        )

        dash.save(flush: true)

        return dash
    }

    Dashboard get(String appCode, int id) {
        Dashboard.findByAppCodeAndUsernameAndId(appCode, username, id)
    }

    Dashboard save(String appCode, int id, String name, String definition) {
        Dashboard dash = get(appCode, id)

        dash.name = name
        dash.definition = definition
        dash.save()

        return dash
    }

    void delete(String appCode, int id) {
        Dashboard userDash = Dashboard.findByAppCodeAndUsernameAndId(appCode, username, id)
        userDash?.delete(flush: true)
    }


    //--------------------------------------
    // Implementation
    //--------------------------------------
    private Dashboard createFromTemplate(String appCode) {
        Dashboard template = Dashboard.findByAppCodeAndUsername(appCode, 'TEMPLATE');

        if (!template) throw new RuntimeException("Unable to find dashboard $appCode for $username (or TEMPLATE)")

        return create(appCode, 'My Dashboard', template.definition)
    }
}
