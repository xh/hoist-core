/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.pref

import grails.gorm.transactions.Transactional
import grails.web.databinding.DataBinder
import io.xh.hoist.BaseService

class PrefDiffService extends BaseService implements DataBinder {

    @Transactional
    void applyRemoteValues(List records) {
        records.each {rec ->
            def pref = Preference.findByName(rec.name),
                vals = rec.remoteValue

            // create new pref based on remote values
            if (!pref) {
                pref = new Preference(vals)
                pref.lastUpdatedBy = authUsername
                pref.save(flush:true)
                logInfo("Pref '${pref.name}' created")
                return
            }

            // apply remote values to existing pref
            if (vals) {
                bindData(pref, vals)
                pref.lastUpdatedBy = authUsername
                pref.save(flush:true)
                logInfo("Pref '${pref.name}' updated")
                return
            }

            // delete local pref if applying null remote value
            if (!vals) {
                def name = pref.name
                pref.delete(flush:true)
                logInfo("Pref '${name}' deleted")
                return
            }
        }
    }
}
