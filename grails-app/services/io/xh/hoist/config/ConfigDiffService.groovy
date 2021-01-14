/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import grails.web.databinding.DataBinder

class ConfigDiffService extends BaseService implements DataBinder {

    @Transactional
    void applyRemoteValues(List records) {
        records.each {rec ->
            def config = AppConfig.findByName(rec.name),
                vals = rec.remoteValue

            // create new config based on remote values
            if (!config) {
                config = new AppConfig(vals)
                config.lastUpdatedBy = username
                config.save(flush:true)
                log.info("Config '${config.name}' created")
                return
            }

            // apply remote values to existing config
            if (vals) {
                bindData(config, vals)
                config.lastUpdatedBy = username
                config.save(flush:true)
                log.info("Config '${config.name}' updated")
                return
            }

            // delete local config if applying null remote value
            if (!vals) {
                def name = config.name
                config.delete(flush:true)
                log.info("Config '${name}' deleted")
                return
            }
        }
    }
}
