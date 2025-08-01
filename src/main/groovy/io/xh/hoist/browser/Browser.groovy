/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.browser

enum Browser {

    CHROME('Chrome'),
    EDGE('Microsoft Edge'),
    FIREFOX('Firefox'),
    IE('Internet Explorer'),
    ISLAND('Island'),
    OPERA('Opera'),
    SAFARI('Safari'),
    IOS_HOMESCREEN('iOS Homescreen'),
    OTHER('Unknown')

    final String displayName

    private Browser(String displayName) {
        this.displayName = displayName
    }

    String toString() {displayName}

    String formatForJSON() {toString()}

    static Browser parse(String str) {
        return values().find {it.displayName == str}
    }

}
