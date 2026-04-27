/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.alertbanner

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhAlertBannerConfig` soft config.
 *
 * Controls availability of the app-wide alert banner feature. Banner content itself is stored
 * separately and delivered to clients via the `/xh/environment` / `/xh/environmentPoll`
 * endpoints — this config only gates whether the feature is enabled.
 */
class AlertBannerConfig extends TypedConfigMap {

    /** True to enable the alert banner system for this application. */
    boolean enabled = true

    AlertBannerConfig(Map args) { init(args) }
}
