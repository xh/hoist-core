/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.browser

import static io.xh.hoist.browser.Browser.*
import static io.xh.hoist.browser.Device.*

import javax.servlet.http.HttpServletRequest

class Utils {

    static Browser getBrowser(HttpServletRequest request) {
        if (!request) return null
        def ua = request?.getHeader('User-Agent'),
            uaHint = request?.getHeader('Sec-Ch-UA')
        findMatch(uaHint, BROWSER_MATCHERS) ?: findMatch(ua, BROWSER_MATCHERS) ?: Browser.OTHER
    }

    static Device getDevice(HttpServletRequest request) {
        if (!request) return null
        def ua = request.getHeader('User-Agent'),
            uaPlatformHint = request.getHeader('Sec-Ch-UA-Platform')
        findMatch(ua, DEVICE_MATCHERS) ?: findMatch(uaPlatformHint, DEVICE_MATCHERS) ?: Device.OTHER
    }

    private static Map<String, Browser> BROWSER_MATCHERS = [
            'Island': ISLAND,   // Island must come before chrome
            'Firefox': FIREFOX, // Firefox must come before "; rv" to prevent identification as IE
            'Edge': EDGE,       // Desktop Edge
            'EdgiOS': EDGE,     // iOS Edge
            'EdgA': EDGE,       // Android Edge
            'Edg': EDGE,        // Desktop Edge
            '; rv': IE,         // IE 11 specific useragent pattern
            'MSIE': IE,
            'Opera': OPERA,     // Opera must come before Chrome and Safari to prevent false positives
            'OPR': OPERA,       // Opera must come before Chrome and Safari to prevent false positives
            'Chrome': CHROME,
            'CriOS': CHROME,    // Mobile Chrome
            'Safari': SAFARI    // Safari must come after Chrome to prevent false positives
    ]

    private static Map<String, Device> DEVICE_MATCHERS = [
        'Windows': WINDOWS,
        'Android': ANDROID,
        'Linux': LINUX,  // Linux must come after Android to prevent false positives
        'iPhone': IPHONE,
        'iPad': IPAD,
        'iPod': IPOD,
        'Mac OS X': MAC, // MAC must come after iPhone|iPad|iPod to prevent false positives
        'Macintosh': MAC,
        'macOS': MAC
    ]

    private static <T extends Object> T findMatch(String header, Map<String, T> targets) {
        header ? targets.find {header.contains(it.key) }?.value : null
    }
}
