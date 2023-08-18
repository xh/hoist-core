/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterTask
import io.xh.hoist.security.Access

import java.util.concurrent.Callable

import static io.xh.hoist.util.Utils.getAppContext

@Access(['HOIST_ADMIN_READER'])
class WebSocketAdminController extends BaseClusterController {

    def allChannels() {
        runOnMember(new AllChannels())
    }
    static class AllChannels extends ClusterTask {
        def doCall() {
            appContext.webSocketService.allChannels
        }
    }

    @Access(['HOIST_ADMIN'])
    def pushToChannel(String channelKey, String topic, String message) {
        runOnMember(new PushToChannel(channelKey: channelKey, topic: topic, message: message))
    }
    static class PushToChannel extends ClusterTask {
        String channelKey
        String topic
        String message

        def doCall() {
            appContext.webSocketService.pushToChannel(channelKey, topic, message)
            return [success: true]
        }
    }
}
