/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.browser

enum Device {

    ANDROID('Android'),
    IPAD('iPad'),
    IPHONE('iPhone'),
    IPOD('iPod'),
    LINUX('Linux'),
    MAC('Mac'),
    WINDOWS('Windows'),
    OTHER('Unknown')

    final String displayName

    private Device(String displayName) {
        this.displayName = displayName
    }

    String toString() {displayName}

    String formatForJSON() {toString()}

    static Device parse(String str) {
        return values().find {it.displayName == str}
    }
    
}
