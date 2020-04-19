/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService

/**
 * Abstract base service for maintaining a list of users for this application and (optionally) for
 * the organization as a whole.
 *
 * Applications must define a concrete implementation of this service with the name 'UserService'.
 *
 * Note that calls to find() in particular are required to be inexpensive / fast to return, as
 * they can be made multiple times with each request to resolve the authenticated user.
 */
@CompileStatic
abstract class BaseUserService extends BaseService {
    abstract HoistUser find(String username)
    abstract List<HoistUser> list(boolean activeOnly)
}