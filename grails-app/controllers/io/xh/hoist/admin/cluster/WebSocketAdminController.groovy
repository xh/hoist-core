/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterJsonRequest
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.getAppContext

@Access(['HOIST_ADMIN_READER'])
class WebSocketAdminController extends BaseController {

    def allChannels(String instance) {
        runOnInstance(new AllChannels(), instance)
    }
    static class AllChannels extends ClusterJsonRequest {
        def doCall() {
            appContext.webSocketService.allChannels
        }
    }

    @Access(['HOIST_ADMIN'])
    def pushToChannel(String channelKey, String topic, String message, String instance) {
        runOnInstance(new PushToChannel(channelKey: channelKey, topic: topic, message: message), instance)
    }
    static class PushToChannel extends ClusterJsonRequest {
        String channelKey
        String topic
        String message

        def doCall() {
            appContext.webSocketService.pushToChannel(channelKey, topic, message)
            return [success: true]
        }
    }
}
