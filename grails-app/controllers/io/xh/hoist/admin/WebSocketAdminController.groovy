/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class WebSocketAdminController extends BaseController {

    def webSocketService

    def allChannels() {
        renderJSON(webSocketService.allChannels)
    }

    @Access(['HOIST_ADMIN'])
    def pushToChannel(String channelKey, String topic, String message) {
        webSocketService.pushToChannel(channelKey, topic, message)
        renderJSON(success: true)
    }
}
