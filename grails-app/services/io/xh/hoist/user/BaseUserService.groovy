/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService

/**
 * Applications must define a concrete implementation of this service with the name 'UserService'
 */
@CompileStatic
abstract class BaseUserService extends BaseService {
    abstract List<HoistUser> list(boolean activeOnly)
    abstract HoistUser find(String username)
}