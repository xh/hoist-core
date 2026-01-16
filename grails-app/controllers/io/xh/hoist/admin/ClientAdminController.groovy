/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessRequiresRole

@AccessRequiresRole('HOIST_ADMIN_READER')
class ClientAdminController extends BaseController {

    def webSocketService

    def allClients() {
        renderJSON(webSocketService.&getAllChannels)
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def pushToClient(String channelKey, String topic, String message) {
        webSocketService.pushToChannel(channelKey, topic, message)
        renderSuccess()
    }
}
