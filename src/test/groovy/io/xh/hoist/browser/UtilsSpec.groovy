/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.browser

import jakarta.servlet.http.HttpServletRequest
import spock.lang.Specification

import static io.xh.hoist.browser.Browser.*
import static io.xh.hoist.browser.Device.*

class UtilsSpec extends Specification {

    static final CHROME_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

    private HttpServletRequest request(String ua, String hints = null, String platform = null) {
        Stub(HttpServletRequest) {
            getHeader('User-Agent') >> ua
            getHeader('Sec-Ch-UA') >> hints
            getHeader('Sec-Ch-UA-Platform') >> platform
        }
    }

    def 'null request yields null browser and device'() {
        expect:
        Utils.getBrowser(null) == null
        Utils.getDevice(null) == null
    }

    def 'getBrowser identifies #expected from user agent'() {
        expect:
        Utils.getBrowser(request(ua)) == expected

        where:
        ua                                                                                                            | expected
        CHROME_UA                                                                                                     | CHROME
        CHROME_UA + ' Edg/120.0.0.0'                                                                                  | EDGE
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 EdgiOS/120.0 Mobile/15E148'      | EDGE
        'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36 EdgA/120.0'             | EDGE
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0'                            | FIREFOX
        'Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko'                                       | IE
        'Mozilla/4.0 (compatible; MSIE 10.0; Windows NT 6.1; Trident/6.0)'                                            | IE
        CHROME_UA + ' OPR/105.0.0.0'                                                                                  | OPERA
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 CriOS/120.0 Mobile/15E148'       | CHROME
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile Safari/604.1' | SAFARI
        CHROME_UA + ' Island/1.0'                                                                                     | ISLAND
        'Mozilla/5.0 GoodAccess/1.0 Chrome/120 Safari/537.36'                                                         | GOOD
        ''                                                                                                            | Browser.OTHER
        'curl/8.0'                                                                                                    | Browser.OTHER
        null                                                                                                          | Browser.OTHER
    }

    def 'client hints take precedence over the user agent for browser'() {
        expect:
        Utils.getBrowser(request(CHROME_UA, hints)) == expected

        where:
        hints                                                          | expected
        '"Chromium";v="122", "Microsoft Edge";v="122"'                 | EDGE
        '"Brave";v="1", "Chromium";v="122"'                            | CHROME
        '"Not(A:Brand";v="99"'                                         | CHROME
    }

    def 'getDevice identifies #expected from user agent'() {
        expect:
        Utils.getDevice(request(ua)) == expected

        where:
        ua                                                                               | expected
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'                                      | WINDOWS
        'Mozilla/5.0 (Linux; Android 14; Pixel 8)'                                       | ANDROID
        'Mozilla/5.0 (X11; Linux x86_64)'                                                | LINUX
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)'                         | IPHONE
        'Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X)'                                  | IPAD
        'Mozilla/5.0 (iPod touch; CPU iPhone OS 12_0 like Mac OS X)'                     | IPHONE
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)'                                | MAC
        'Mozilla/5.0 (X11; CrOS x86_64 14541.0.0)'                                       | Device.OTHER
        null                                                                             | Device.OTHER
    }

    def 'platform hint takes precedence over the user agent for device'() {
        expect:
        Utils.getDevice(request('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', null, platform)) == expected

        where:
        platform      | expected
        '"Windows"'   | WINDOWS
        '"macOS"'     | MAC
    }
}
