/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.http

import groovy.transform.CompileStatic

import javax.servlet.http.HttpServletResponse

@CompileStatic
class HttpUtils {

    static setResponseCache(HttpServletResponse response, int minutes) {
        minutes = Math.max(0, minutes)

        long maxAgeSecs = 60 * minutes

        String cacheControl = (minutes == 0 ? 'no-cache' : 'public, max-age=' + maxAgeSecs)
        response.setHeader('Cache-Control', cacheControl)

        long expiresMs = System.currentTimeMillis() + (maxAgeSecs * 1000)
        response.setDateHeader('Expires', expiresMs);
    }

    static List parseHostPort(String str) {
        if (str.contains(':')) {
            String[] parts = str.split(':')
            return [parts[0], parts[1] as Integer]
        } else {
            return [str, null]
        }
    }
    
}
