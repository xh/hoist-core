/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

import static io.xh.hoist.json.JSONParser.parseObject

@Access(['HOIST_ADMIN'])
class AlertBannerAdminController extends BaseController {

    def alertBannerService

    def alertSpec() {
        renderJSON(alertBannerService.alertSpec)
    }

    def setAlertSpec(String value) {
        alertBannerService.setAlertSpec(parseObject(value))
        renderJSON(success: true)
    }
}
