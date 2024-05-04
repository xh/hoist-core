package io.xh.hoist.security.oauth

/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

import static java.util.Collections.emptyMap

@CompileStatic
abstract class BaseOauthService extends BaseService {

    /** Return OauthConfig used by browser OauthService. */
    abstract Map getClientConfig()

    abstract TokenValidationResult validateToken(String token)

    protected Map getOauthConfig() {
        return Utils.configService.getMap('xhOauthConfig', emptyMap())
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhOauthConfig')
    ]}
}
