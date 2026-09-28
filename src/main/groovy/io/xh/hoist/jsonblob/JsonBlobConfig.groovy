/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhJsonBlobConfig` soft config.
 */
class JsonBlobConfig extends TypedConfigMap {

    /**
     * Roles permitted to create, modify, and archive global (null-owner) blobs, keyed by blob
     * `type`, with `*` as the fallback for types not listed. Include the role `*` to allow all users.
     */
    Map<String, List<String>> globalWriteRoles = ['*': ['HOIST_ADMIN']]

    JsonBlobConfig(Map args) { init(args) }
}
