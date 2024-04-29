package io.xh.hoist.security.oauth

/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */


import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

@CompileStatic
abstract class BaseOauthService extends BaseService {

    /** Return OauthConfig used by browser OauthService. */
    abstract Map getClientConfig()

    abstract TokenValidationResult validateToken(String token)

    protected Map getOauthConfig() {
        return Utils.configService.getMap('oauthConfig')
    }

    Map getAdminStats() {[
        config: configForAdminStats('oauthConfig')
    ]}
}
