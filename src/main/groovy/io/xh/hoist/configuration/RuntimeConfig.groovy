/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.configuration

import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static io.xh.hoist.util.Utils.withDelegate

/**
 * Default Application config.
 *
 * Main entry point to be called from runtime.groovy
 */
class RuntimeConfig {

    /**
     * All apps should call this from runtime.groovy
     * to setup necessary default configurations
     * @param script
     */
    static void defaultConfig(Script script) {
        withDelegate(script) {
            grails.serverURL = getInstanceConfig('serverURL')
        }
    }

    /**
     * Call this from runtime.groovy
     * to setup an in memory H2 DB instead of
     * a MySQL or SQL Server DB.  This H2 DB option
     * is intended only for early stages of development,
     * before a production ready DB has been set up.
     *
     * @param script
     */
    static void h2Config(Script script) {
        withDelegate(script) {
            dataSource {
                pooled = true
                jmxExport = true
                driverClassName = "org.h2.Driver"
                username = "sa"
                password = ""
            }
            environments {
                development {
                    dataSource {
                        dbCreate = "create-drop"
                        url = "jdbc:h2:mem:devDb;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE"
                    }
                }
            }
        }
    }
}
