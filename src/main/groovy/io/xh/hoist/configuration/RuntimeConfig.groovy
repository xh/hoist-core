/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.configuration

import org.hibernate.dialect.H2Dialect

import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static io.xh.hoist.util.Utils.withDelegate

/**
 * Default Application config.
 *
 * Main entry point to be called from runtime.groovy
 */
class RuntimeConfig {

    /**
     * All apps should call this from runtime.groovy to setup necessary default configurations
     */
    static void defaultConfig(Script script) {
        withDelegate(script) {
            grails.serverURL = getInstanceConfig('serverURL')
        }
    }

    /**
     * Call this from runtime.groovy to setup an in memory H2 DB instead of MySQL or SQL Server.
     * This option is intended only for early stages of development, before a production-ready
     * database has been provisioned. Data is transient, NOT intended for actual deployments!
     *
     * Note you will need to add a dependency to your app's build.gradle file:
     *      `runtimeOnly "com.h2database:h2:2.2.224"` (check and use latest/suitable version).
     */
    static void h2Config(Script script) {
        withDelegate(script) {
            dataSource {
                pooled = true
                jmxExport = true
                driverClassName = "org.h2.Driver"
                dialect = H2Dialect
                username = "sa"
                password = ""
            }
            environments {
                development {
                    dataSource {
                        dbCreate = "create-drop"
                        // `value` is a reserved word in H2 v2.x but used by Hoist AppConfig.
                        // We can workaround with NON_KEYWORDS=VALUE in the JDBC URL below.
                        url = "jdbc:h2:mem:devDb;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=VALUE"
                    }
                }
            }
        }
    }
}
