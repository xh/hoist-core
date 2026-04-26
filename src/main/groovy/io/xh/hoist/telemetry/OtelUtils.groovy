/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry

import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig

class OtelUtils {

    /**
     * True if OTLP export (metrics + traces) should run in local development. Defaults to false —
     * set the `otlpEnabledInLocalDev` instance config to `'true'` to opt in. No effect elsewhere.
     **/
    static boolean getOtlpEnabledInLocalDev() {
        return getInstanceConfig('otlpEnabledInLocalDev') == 'true'
    }
}
