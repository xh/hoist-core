/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.blueprint

import io.xh.hoist.exception.RoutineRuntimeException

/**
 * Thrown by {@link BlueprintService} when a draft save or publish names a base version that is
 * no longer the draft's current version.
 */
class BlueprintStaleBaseException extends RoutineRuntimeException {

    final long base
    final long current

    BlueprintStaleBaseException(long base, long current) {
        super("The draft is at version $current, but this was based on version $base. Open it again.")
        this.base = base
        this.current = current
    }
}
