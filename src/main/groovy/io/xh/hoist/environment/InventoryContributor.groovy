/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.environment

/**
 * Implement on any `BaseService` to contribute app-specific data to the inventory report served
 * by {@link EnvironmentService#getInventory}. Contributions appear in the report's `custom`
 * section, keyed by the contributing service's bean name.
 *
 * Use to report details a central inventory would want but Hoist cannot know - e.g. versions of
 * upstream systems the app integrates with, entitlement counts, or app-level feature flags.
 *
 * Return only JSON-serializable data that is appropriate for the report's audience. Exceptions
 * are caught and reported in place of the contribution, so a failing contributor never breaks
 * the overall report.
 */
interface InventoryContributor {
    Map getInventoryData()
}
