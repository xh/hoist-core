/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import static io.xh.hoist.json.JSONParser.parseArray
import io.xh.hoist.security.Access

import static io.xh.hoist.util.ClusterUtils.runOnAllInstancesAsJson
import static io.xh.hoist.util.ClusterUtils.runOnInstanceAsJson

@Access(['HOIST_ADMIN_READER'])
class ClientAdminController extends BaseController {

    def webSocketService

    def allClients() {
        // Need to serialize the complex WebSocketsSessions as JSON -- parse and rejoin here
        def ret = runOnAllInstancesAsJson(webSocketService.&getAllChannels)
            .collectMany { it.value.exception ? [] : parseArray(it.value.value)}
        renderJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def pushToClient(String channelKey, String topic, String message, String instance) {
        def ret = runOnInstanceAsJson(webSocketService.&pushToChannel, instance, [channelKey, topic, message])
        renderClusterJSON(ret)
    }
}
