/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import static io.xh.hoist.util.ClusterUtils.runOnInstanceAsJson

@Access(['HOIST_ADMIN_READER'])
class WebSocketAdminController extends BaseController {

    def webSocketService

    def allChannels(String instance) {
        def ret = runOnInstanceAsJson(webSocketService.&getAllChannels, instance)
        renderClusterJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def pushToChannel(String channelKey, String topic, String message, String instance) {
        def ret = runOnInstanceAsJson(webSocketService.&pushToChannel, instance, [channelKey, topic, message])
        renderClusterJSON(ret)
    }
}
