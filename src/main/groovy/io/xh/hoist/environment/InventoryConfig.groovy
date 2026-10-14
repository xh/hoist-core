/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.environment

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhInventoryConfig` soft config, governing the opt-in inventory
 * report served by `EnvironmentService` via the whitelisted `/xh/inventory` endpoint.
 *
 * The report is designed for automated, cross-app inventory sweeps - e.g. a central job that
 * polls every Hoist deployment within a secure network to collect versions, libraries, runtime
 * details, feature usage, and activity summaries. See `docs/environment.md`.
 */
class InventoryConfig extends TypedConfigMap {

    /** Master switch. The `/xh/inventory` endpoint returns 404 unless true. */
    boolean enabled = false

    /**
     * True to require callers to present the token stored in the `xhInventoryToken` pwd config,
     * via either an `X-XH-Inventory-Token` or `Authorization: Bearer` request header.
     * Authenticated users with `HOIST_ADMIN_READER` can always read the report without a token.
     *
     * Set to false only where network-level controls already restrict access to the endpoint.
     */
    boolean requireToken = true

    /**
     * Names of report sections to omit. Available sections are listed in
     * {@link EnvironmentService#INVENTORY_SECTIONS}.
     */
    List<String> excludeSections = []

    /** Trailing window (days) over which the `usage` section summarizes activity tracking. */
    Integer usageDays = 30

    /**
     * True to include the primary JDBC connection URL in the `database` section. Off by default,
     * as some drivers accept credentials within the URL.
     */
    boolean includeDatabaseUrl = false

    /**
     * Minutes to cache the generated report on each instance. Sweeps typically run infrequently,
     * but caching bounds the cost of the activity queries and cluster fan-out if polled often.
     */
    Integer cacheMins = 10

    /**
     * Free-form metadata to include verbatim in the report - e.g. owning team, business unit,
     * client, support contact, or internal asset ID. Lets operations teams annotate a deployment
     * for central reporting without an app code change.
     */
    Map tags = [:]

    InventoryConfig(Map args) { init(args) }
}
