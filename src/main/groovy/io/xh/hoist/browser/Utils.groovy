/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.browser

import static io.xh.hoist.browser.Browser.*
import static io.xh.hoist.browser.Device.*

import jakarta.servlet.http.HttpServletRequest

class Utils {

    static Browser getBrowser(HttpServletRequest request) {
        if (!request) return null
        def ua = safeHeader(request, 'User-Agent'),
            uaHints = safeHeader(request, 'Sec-Ch-UA')
        findMatch(uaHints, BROWSER_MATCHERS) ?: findMatch(ua, BROWSER_MATCHERS) ?: Browser.OTHER
    }

    static Device getDevice(HttpServletRequest request) {
        if (!request) return null
        def ua = safeHeader(request, 'User-Agent'),
            uaPlatformHints = safeHeader(request, 'Sec-Ch-UA-Platform')
        findMatch(uaPlatformHints, DEVICE_MATCHERS) ?: findMatch(ua, DEVICE_MATCHERS) ?: Device.OTHER
    }

    /**
     * Read a header from a servlet request, returning null if the underlying RequestFacade
     * has been recycled by Tomcat. Tolerates identity/observability calls that arrive on
     * threads whose request reference is no longer valid (e.g. async continuations).
     */
    static String safeHeader(HttpServletRequest request, String name) {
        try {
            return request?.getHeader(name)
        } catch (IllegalStateException ignored) {
            return null
        }
    }

    //--------------------
    // Implementation
    //--------------------
    private static Map<String, Browser> BROWSER_MATCHERS = [
            'Island': ISLAND,   // Island must come before chrome
            'GoodAccess': GOOD, // Good must come before chrome and safari
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
