/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.feedback.Feedback
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class FeedbackAdminController extends AdminRestController {
    static restTarget = Feedback
    static trackChanges = false
}
