/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config.impl

import grails.gorm.transactions.Transactional
import grails.web.databinding.DataBinder
import io.xh.hoist.BaseService
import io.xh.hoist.config.AppConfig

/**
 * Internal support service backing the Admin Console's config diff/import workflow — applies
 * a batch of remote `AppConfig` values onto the local database (creating, updating, or
 * deleting rows to match the supplied snapshot).
 *
 * @internal - not intended for direct use by applications.
 */
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
                config.save(flush: true, failOnError: true)
                logInfo("Config '${config.name}' created")
                return
            }

            // apply remote values to existing config
            if (vals) {
                bindData(config, vals)
                config.lastUpdatedBy = authUsername
                config.save(flush: true, failOnError: true)
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
