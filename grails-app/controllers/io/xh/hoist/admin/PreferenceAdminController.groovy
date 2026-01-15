/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.pref.Preference
import io.xh.hoist.security.AccessRequiresRole

@AccessRequiresRole('HOIST_ADMIN_READER')
class PreferenceAdminController extends AdminRestController {

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
