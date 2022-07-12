/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import grails.gorm.transactions.Transactional
import grails.web.databinding.DataBinder
import io.xh.hoist.BaseService

class ConfigDiffService extends BaseService implements DataBinder {

    @Transactional
    void applyRemoteValues(List records) {
        records.each {rec ->
            def config = AppConfig.findByName(rec.name),
                vals = rec.remoteValue

            // create new config based on remote values
            if (!config) {
                config = new AppConfig(vals)
                config.lastUpdatedBy = authUsername
                config.save(flush:true)
                logInfo("Config '${config.name}' created")
                return
            }

            // apply remote values to existing config
            if (vals) {
                bindData(config, vals)
                config.lastUpdatedBy = authUsername
                config.save(flush:true)
                logInfo("Config '${config.name}' updated")
                return
            }

            // delete local config if applying null remote value
            if (!vals) {
                def name = config.name
                config.delete(flush:true)
                logInfo("Config '${name}' deleted")
                return
            }
        }
    }
}
