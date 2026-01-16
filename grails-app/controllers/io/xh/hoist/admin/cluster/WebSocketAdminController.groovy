/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessRequiresRole

import static io.xh.hoist.util.ClusterUtils.runOnInstanceAsJson

@AccessRequiresRole('HOIST_ADMIN_READER')
class WebSocketAdminController extends BaseController {

    def webSocketService

    def allChannels(String instance) {
        def ret = runOnInstanceAsJson(webSocketService.&getLocalChannels, instance)
        renderClusterJSON(ret)
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def pushToChannel(String channelKey, String topic, String message, String instance) {
        def ret = runOnInstanceAsJson(webSocketService.&pushToChannel, instance, [channelKey, topic, message])
        renderClusterJSON(ret)
    }
}
