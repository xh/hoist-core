/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry

import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig
import static io.xh.hoist.util.Utils.isLocalDevelopment

class OtelUtils {

    /**
     * True if OTLP export (metrics + traces) is suppressed. Defaults to false in local dev,
     * true otherwise — override via `suppressOtlpExport` instance config.
     **/
    static boolean getSuppressOtlpExport() {
        def val = getInstanceConfig('suppressOtlpExport')
        return val != null ? val == 'true' : isLocalDevelopment
    }
}
