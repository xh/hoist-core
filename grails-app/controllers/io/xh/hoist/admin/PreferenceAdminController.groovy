/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.pref.Preference
import io.xh.hoist.RestController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class PreferenceAdminController extends RestController {

    static restTarget = Preference
    static trackChanges = true

    def lookupData() {
        renderJSON (
                types: Preference.TYPES,
                groupNames: Preference.list().collect{it.groupName}.unique().sort()
        )
    }

    protected void preprocessSubmit(Map submit) {
        submit.lastUpdatedBy = authUsername
    }

}
