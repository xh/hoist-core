/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.util.Holders
import io.xh.hoist.util.Utils

class BootStrap {

    def init = {servletContext ->
        checkEnvironment()
        logStartupMsg()
        def services = Utils.xhServices.findAll {it.class.canonicalName.startsWith('io.xh.hoist')}
        BaseService.parallelInit(services)
    }

    def destroy = {}


    //------------------------
    // Implementation
    //------------------------
    private void checkEnvironment() {
        def supportedEnvironments = Utils.supportedEnvironments,
            appEnvironment = Utils.appEnvironment.toString()

        if (!supportedEnvironments.contains(appEnvironment)) {
            throw new RuntimeException("Environment not supported for this application: ${appEnvironment}")
        }
    }

    private void logStartupMsg() {
        def hoist = Holders.currentPluginManager().getGrailsPlugin('hoist-core')

        log.info("""
\n
 __  __     ______     __     ______     ______
/\\ \\_\\ \\   /\\  __ \\   /\\ \\   /\\  ___\\   /\\__  _\\
\\ \\  __ \\  \\ \\ \\/\\ \\  \\ \\ \\  \\ \\___  \\  \\/_/\\ \\/
 \\ \\_\\ \\_\\  \\ \\_____\\  \\ \\_\\  \\/\\_____\\    \\ \\_\\
  \\/_/\\/_/   \\/_____/   \\/_/   \\/_____/     \\/_/
\n
          Hoist v${hoist.version} - ${Utils.getAppEnvironment()}
          Extremely Heavy Industries - http://xh.io
\n
        """)
    }
    
}
