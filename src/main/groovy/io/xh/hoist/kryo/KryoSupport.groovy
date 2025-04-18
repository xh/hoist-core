/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.kryo

import com.hazelcast.config.Config
import com.hazelcast.config.GlobalSerializerConfig

/**
 * Support for serialization via Kryo.
 */
class KryoSupport {
        static setAsGlobalSerializer(Config config) {
            def gsc = config.serializationConfig.globalSerializerConfig ?= new GlobalSerializerConfig()
            gsc.className = KryoSerializer.class.name

            // Avoid stomping on Hibernate Cache Serialization which fails with Kryo
            // Consider replacing this with an *explicit* exclusion.
            gsc.overrideJavaSerialization = false
        }
}

