/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

import static io.xh.hoist.util.ClusterUtils.runOnInstanceAsJson
import static io.xh.hoist.util.ClusterUtils.runOnAllInstances

@Access(['HOIST_ADMIN_READER'])
class ClientAdminController extends BaseController {

    def webSocketService

    def allClients() {
        def ret = runOnAllInstances(webSocketService.&getAllChannels)
        ret = ret.collectMany {it.value.value}
        renderJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def pushToClient(String channelKey, String topic, String message, String instance) {
        def ret = runOnInstanceAsJson(webSocketService.&pushToChannel, instance, [channelKey, topic, message])
        renderClusterJSON(ret)
    }
}
