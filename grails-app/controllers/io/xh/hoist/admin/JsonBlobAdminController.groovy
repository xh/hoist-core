/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.jsonblob.JsonBlob
import io.xh.hoist.RestController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class JsonBlobAdminController extends RestController {
    static restTarget = JsonBlob
    static trackChanges = true

    def lookupData() {
        renderJSON(
            types: JsonBlob.createCriteria().list{
                projections { distinct('type') }
            }.sort()
        )
    }

    protected void preprocessSubmit(Map submit) {
        // Note explicit true/false check to distinguish from undefined
        if (submit.archived == true) {
            submit.archivedDate = new Date().getTime()
        } else if (submit.archived == false) {
            submit.archivedDate = 0
        }
        submit.lastUpdatedBy = username
    }
}
