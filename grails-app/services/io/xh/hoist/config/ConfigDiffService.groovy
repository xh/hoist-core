/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import io.xh.hoist.BaseService
import grails.web.databinding.DataBinder

class ConfigDiffService extends BaseService implements DataBinder {

    void applyRemoteValues(List records) {
        records.each {rec ->
            def config = AppConfig.findByName(rec.name),
                vals = rec.remoteValue

            // create new config based on remote values
            if (!config) {
                config = new AppConfig(vals)
                config.save(flush:true)
                log.info("Config '${config.name}' created")
                return
            }

            // apply remote values to existing config
            if (vals) {
                // ensure we NEVER overwrite an existing pwd val
                if (config.valueType == 'pwd') removeValueFields(vals)
                bindData(config, vals)
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

    // untested as of yet
    void removeValueFields(vals) {
        vals.remove('prodValue')
        vals.remove('betaValue')
        vals.remove('prodValue')
        vals.remove('devValue')
    }
}
