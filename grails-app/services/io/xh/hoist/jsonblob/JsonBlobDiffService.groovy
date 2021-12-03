/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import grails.web.databinding.DataBinder
import io.xh.hoist.BaseService

class JsonBlobDiffService extends BaseService implements DataBinder {

    void applyRemoteValues(List records) {
        records.each {rec ->
            def blob = JsonBlob.findByTypeAndOwnerAndNameAndArchivedDate(rec.type, rec.owner, rec.name, rec.archivedDate),
                vals = rec.remoteValue

            // create new blob based on remote values
            if (!blob) {
                blob = new JsonBlob(vals)
                blob.lastUpdatedBy = authUsername
                blob.save(flush:true)
                logInfo("JsonBlob '${blob.name}' created")
                return
            }

            // apply remote values to existing config
            if (vals) {
                bindData(blob, vals)
                blob.lastUpdatedBy = authUsername
                blob.save(flush:true)
                logInfo("JsonBlob '${blob.name}' updated")
                return
            }

            // delete local blob if applying null remote value
            if (!vals) {
                def name = blob.name
                blob.delete(flush:true)
                logInfo("JsonBlob '${name}' deleted")
                return
            }
        }
    }

}
