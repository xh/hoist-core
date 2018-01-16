/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.browser

import grails.util.Holders
import groovy.util.logging.Slf4j
import static io.xh.hoist.browser.Browser.*
import static io.xh.hoist.browser.Device.*

import javax.servlet.http.HttpServletRequest

@Slf4j
class Utils {
    
    private static Map<String, Browser> BROWSERS_MATCHERS = [
            'Firefox': FIREFOX, // Firefox must come before "; rv" to prevent identification as IE
            'Edge': EDGE,
            '; rv': IE,         // IE 11 specific useragent pattern
            'MSIE': IE,
            'Opera': OPERA,     // Opera must come before Chrome and Safari to prevent false positives
            'OPR': OPERA,       // Opera must come before Chrome and Safari to prevent false positives
            'Chrome': CHROME,
            'CriOS': CHROME,    // Mobile Chrome
            'Safari': SAFARI    // Safari must come after Chrome to prevent false positives
    ]

    private static Map<String, Map<String,String>> BROWSER_VERSION_MATCHERS = [
            'Edge': [start: 'Edge/', end: null],
            '; rv': [start: '; rv:', end: ')'],
            'MSIE': [start: 'MSIE ', end: ';'],
            'Opera': [start: 'Opera/', end: null], // Opera must come before Chrome and Safari to prevent false positives
            'OPR': [start: 'OPR/', end: null], // Opera must come before Chrome and Safari to prevent false positives
            'Chrome': [start: 'Chrome/', end: ' '],
            'CriOS': [start: 'CriOS/', end: ' '], // Mobile Chrome
            'Safari': [start: 'Version/', end: ' '], // Safari uses nonstandard 'Version/', and must come after Chrome to prevent false positives
            'Firefox': [start: 'Firefox/', end: null]
    ]

    private static Map<String, Device> DEVICE_MATCHERS = [
            'Windows': WINDOWS,
            'Android': ANDROID,
            'Linux': LINUX,  // Linux must come after Android to prevent false positives
            'Macintosh': MAC,
            'iPhone': IPHONE,
            'iPad': IPAD,
            'iPod': IPOD,
            'Mac OS X': MAC  // Mac OS X must come after iPhone|iPad|iPod to prevent false positives
    ]

    private static List MOBILE_DEVICES = [ANDROID, IPAD, IPOD, IPHONE]
    private static List IOS_DEVICES = [IPAD, IPOD, IPHONE]

    static Browser getBrowser(String userAgent) {
        if (!userAgent) return null

        // 1) Standard Browser
        def match = BROWSERS_MATCHERS.find {key, value -> userAgent.contains(key) }?.value
        if (match) return match

        // 2) Catch special iOS homescreen
        // iOS devices running in home-screen mode present a non-standard user-agent
        // which prevents us from extracting a standardized browser name or version.
        def isIos = isIosDevice(userAgent),
            version = getBrowserVersion(userAgent),
            majorVersionString = version?.tokenize('.')?.first(),
            majorVersion = majorVersionString?.isInteger() ? majorVersionString as Integer : null
        if (isIos && !majorVersion) return IOS_HOMESCREEN

        // 3) Next big thing
        return Browser.OTHER
    }

    static String getBrowserVersion(String userAgent) {
        if (!userAgent) return null
        def matcher = BROWSER_VERSION_MATCHERS.find {key, value -> userAgent.contains(key) }?.value
        if (!matcher || !userAgent.contains(matcher.start) || (matcher.end && !userAgent.contains(matcher.end))) return 'Unknown'

        def startStr = userAgent.substring(userAgent.indexOf(matcher.start) + matcher.start.length()),
            version = matcher.end ? startStr.substring(0, startStr.indexOf(matcher.end)) : startStr

        return version ?: 'Unknown'
    }

    static Device getDevice(String userAgent) {
        if (!userAgent) return null
        def match = DEVICE_MATCHERS.find {key, value -> userAgent.contains(key)}?.value
        return match ?: Device.OTHER
    }

    static boolean isMobile(String userAgent) {
        return MOBILE_DEVICES.contains(getDevice(userAgent))
    }

    static boolean isIosDevice(String userAgent) {
        return IOS_DEVICES.contains(getDevice(userAgent))
    }

    static Map<Browser, Object> getSupportedDesktopBrowsers() {
        def desktopBrowsers = Holders.grailsApplication.config.hoist.supportedDesktopBrowsers
        return desktopBrowsers ?: [EDGE:0, IE:11, CHROME:45, FIREFOX:52, SAFARI:9, OTHER:false]
    }

    static Map<Browser, Object> getSupportedMobileBrowsers() {
        def mobileBrowsers = Holders.grailsApplication.config.hoist.supportedMobileBrowsers
        return mobileBrowsers ?: [EDGE:0, IE:11, CHROME:45, FIREFOX:52, SAFARI:9, IOS_HOMESCREEN:true, OTHER:false]
    }

    /**
     * Is the given userAgent supported by this application?
     *
     * See getSupportedXXBrowsers().  Applications can customize this behavior by specifying
     * maps for 'hoist.supportedMobileBrowsers' or 'hoist.supportedDesktopBrowsers' of the form
     * [BROWSER: [VALUE]], where value can be either a boolean, or a minimum major version number
     *
     *  e.g.  [CHROME:52, IE:11, OTHER:false]
     */
    static boolean browserSupported(String userAgent) {
        def device = getDevice(userAgent),
            browser = getBrowser(userAgent),
            browserName = browser.name(),
            version = getBrowserVersion(userAgent),
            majorVersion = getMajorVersion(version),
            supportedBrowsers = isMobile(userAgent) ? getSupportedMobileBrowsers() : getSupportedDesktopBrowsers(),
            support = supportedBrowsers.containsKey(browserName) ? supportedBrowsers[browserName] : null

        log.debug("Evaluating browser support for $device | $browser v$version | majorVersion: $majorVersion | userAgent: $userAgent")
        
        if (support == true) return true

        if (support instanceof Integer && majorVersion != null) {
            return majorVersion >= support
        }

        return false
    }

    // Request-based convenience methods.
    static String  getAgent(HttpServletRequest request)             {request?.getHeader('User-Agent')}
    static Browser getBrowser(HttpServletRequest request)           {getBrowser(getAgent(request))}
    static String  getBrowserVersion(HttpServletRequest request)    {getBrowserVersion(getAgent(request))}
    static Device  getDevice(HttpServletRequest request)            {getDevice(getAgent(request))}
    static boolean isMobile(HttpServletRequest request)             {isMobile(getAgent(request))}
    static boolean browserSupported(HttpServletRequest request)     {browserSupported(getAgent(request))}

    
    //--------------------
    // Implementation
    //--------------------
    static private Integer getMajorVersion(String version) {
        def majorVersion = version?.tokenize('.')?.first()
        return majorVersion?.isInteger() ? majorVersion as Integer : null
    }
    
}
