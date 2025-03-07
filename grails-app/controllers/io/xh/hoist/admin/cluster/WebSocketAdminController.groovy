/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class WebSocketAdminController extends BaseController {

    def webSocketService

    def allChannels(String instance) {
        def ret = webSocketService.runOnInstance('getAllChannels', instance: instance, asJson: true)
        renderClusterJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def pushToChannel(String channelKey, String topic, String message, String instance) {
        def ret =  webSocketService.runOnInstance(
            'pushToChannel',
            args: [channelKey, topic, message],
            instance: instance,
            asJson: true
        )
        renderClusterJSON(ret)
    }
}
