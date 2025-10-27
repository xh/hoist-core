/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class AlertBannerAdminController extends BaseController {

    def alertBannerService

    def alertSpec() {
        renderJSON(alertBannerService.alertSpec)
    }

    def alertPresets() {
        renderJSON(alertBannerService.alertPresets)
    }

    @Access(['HOIST_ADMIN'])
    def setAlertSpec() {
        alertBannerService.setAlertSpec(parseRequestJSON())
        renderSuccess()
    }

    @Access(['HOIST_ADMIN'])
    def setAlertPresets() {
        alertBannerService.setAlertPresets(parseRequestJSONArray())
        renderSuccess()
    }
}
