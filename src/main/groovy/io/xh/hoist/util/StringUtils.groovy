/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import groovy.transform.CompileStatic

@CompileStatic
class StringUtils {
    static String elide(String str, int len) {
        if (str.size() < len)   return str
        if (str.size() <= 3)    return '...'

        str = str.substring(0, len - 3)      // chop string, with room for ellipsis
        str = str.replaceAll(/\w+$/, '')    // trim back to previous word boundary,
        str = str.replaceAll(/\s+$/, '')    // also trim trailing whitespace

        return str + '...'
    }
}
