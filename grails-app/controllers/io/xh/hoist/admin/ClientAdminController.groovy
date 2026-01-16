/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import static io.xh.hoist.json.JSONParser.parseArray
import io.xh.hoist.security.AccessRequiresRole

import static io.xh.hoist.util.ClusterUtils.runOnAllInstancesAsJson

@AccessRequiresRole('HOIST_ADMIN_READER')
class ClientAdminController extends BaseController {

    def webSocketService

    def allClients() {
        // Need to serialize the complex WebSocketsSessions as JSON -- parse and rejoin here
        def ret = runOnAllInstancesAsJson(webSocketService.&getLocalChannels)
            .collectMany { it.value.exception ? [] : parseArray(it.value.value)}
        renderJSON(ret)
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def pushToClient(String channelKey, String topic, String message) {
        webSocketService.pushToChannel(channelKey, topic, message)
        renderSuccess()
    }
}
