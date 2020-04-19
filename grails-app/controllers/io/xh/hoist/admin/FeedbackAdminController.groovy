/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.RestController
import io.xh.hoist.feedback.Feedback
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class FeedbackAdminController extends RestController {
    static restTarget = Feedback
    static trackChanges = false
}