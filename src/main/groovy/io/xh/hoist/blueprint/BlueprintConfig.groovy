/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.blueprint

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhBlueprintConfig` soft config, read by {@link BlueprintService}.
 *
 * Roles here grant rights across every blueprint, beside the owner, writers and readers each
 * blueprint lists itself. Include the role `*` in a list to grant its right to all users.
 */
class BlueprintConfig extends TypedConfigMap {

    /** True to enable blueprint storage. While false, every `BlueprintService` call is refused. */
    boolean enabled = false

    /** Roles with write and publish access to every blueprint, and the right to manage its access. */
    List<String> adminRoles = ['HOIST_ADMIN']

    /** Roles permitted to create blueprints, including copies. */
    List<String> createRoles = ['HOIST_ADMIN']

    /**
     * Roles that may share a blueprint with everyone, by adding `*` to its readers. `adminRoles`
     * may always do so.
     */
    List<String> globalShareRoles = ['HOIST_ADMIN']

    /** Roles a user with write access also needs to publish. Empty, write access suffices. */
    List<String> publishRoles = []

    /** Max size of a stored blueprint, or of one personalization's ops, as serialized JSON bytes. */
    int maxBytes = 2 * 1024 * 1024

    /** Max personalizations each user may keep for one blueprint. */
    int maxPersonalizations = 50

    BlueprintConfig(Map args) { init(args) }
}
