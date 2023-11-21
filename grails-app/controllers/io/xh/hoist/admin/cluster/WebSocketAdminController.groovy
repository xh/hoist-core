/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.getAppContext

@Access(['HOIST_ADMIN_READER'])
class WebSocketAdminController extends BaseClusterController {

    def allChannels(String instance) {
        runOnInstance(new AllChannels(), instance)
    }
    static class AllChannels extends ClusterRequest {
        def doCall() {
            appContext.webSocketService.allChannels*.formatForJSON()
        }
    }

    @Access(['HOIST_ADMIN'])
    def pushToChannel(String channelKey, String topic, String message, String instance) {
        runOnInstance(new PushToChannel(channelKey: channelKey, topic: topic, message: message), instance)
    }
    static class PushToChannel extends ClusterRequest {
        String channelKey
        String topic
        String message

        def doCall() {
            appContext.webSocketService.pushToChannel(channelKey, topic, message)
            return [success: true]
        }
    }
}
