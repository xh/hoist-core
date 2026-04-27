/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.config


/**
 * Typed representation of the `xhIdleConfig` soft config, governing client idle-mode behavior
 * (suspending background requests and prompting a reload after prolonged inactivity).
 *
 * Consumed by the client-side `IdleService` in hoist-react. A typed class is maintained here
 * so framework code is the single source of truth for the shape and defaults surfaced to the
 * client; server-side readers are not expected today.
 */
class IdleConfig extends TypedConfigMap {

    /** Default idle timeout (minutes). -1 disables idle-mode for apps without an override. */
    Integer timeout = 120

    /**
     * Per-app overrides, keyed by `XH.clientAppCode` → minutes. Free-form — keys are the
     * arbitrary codes of each sub-app. Remains a plain Map rather than a typed section.
     */
    Map<String, Integer> appTimeouts = [:]

    IdleConfig(Map args) { init(args) }
}
