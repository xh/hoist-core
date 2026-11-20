/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.blueprint

import io.xh.hoist.exception.NotFoundException
import io.xh.hoist.exception.RoutineException

/**
 * Thrown by {@link BlueprintService} for a blueprint that does not exist or that the current user
 * may not read, so the two look the same. Routine, as a stale link or deleted copy is expected.
 */
class BlueprintNotFoundException extends NotFoundException implements RoutineException {
    BlueprintNotFoundException(String token) {
        super("Blueprint '$token' not found")
    }
}
