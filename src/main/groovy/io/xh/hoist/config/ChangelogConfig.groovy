/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.config


/**
 * Typed representation of the `xhChangelogConfig` soft config, governing the built-in
 * changelog / release-notes viewer.
 *
 * This config is consumed by the client-side `ChangelogService` in hoist-react — it has no
 * server-side readers today. A typed class is maintained here so the framework is the single
 * source of truth for the shape and defaults surfaced to the client.
 */
class ChangelogConfig extends TypedConfigMap {

    /** Master switch for the in-app changelog viewer. */
    boolean enabled = true

    /** Semver strings to suppress from the changelog display (e.g. unreleased or withdrawn versions). */
    List<String> excludedVersions = []

    /** Change categories to suppress from the changelog display. */
    List<String> excludedCategories = []

    /** If non-empty, only users with at least one of these roles see the changelog. */
    List<String> limitToRoles = []

    ChangelogConfig(Map args) { init(args) }
}
