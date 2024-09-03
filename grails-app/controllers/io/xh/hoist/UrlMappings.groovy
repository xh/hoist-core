/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

class UrlMappings {

    static mappings = {

        //------------------------
        // Default Grails Conventions
        //------------------------
        "/"(controller: 'default')
        "/$controller/$action?/$id?(.$format)?"{}

        "404" (controller: 'xh', action: 'notFound')
        "/ping" (controller: 'xh', action: 'version')

        //------------------------
        // Rest Support
        //------------------------
        "/rest/$controller/lookupData"{action='lookupData'}
        "/rest/$controller/bulkDelete"{action='bulkDelete'}
        "/rest/$controller/bulkUpdate"{action='bulkUpdate'}
        "/rest/$controller/$id?"{
            action = [POST: 'create', GET: 'read', PUT: 'update', DELETE: 'delete']
        }

        //------------------------
        // Proxy Support
        //------------------------
        "/proxy/$name/$url**"{
            controller = 'proxyImpl'
        }

    }

}
