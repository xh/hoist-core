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

/**
 * Minimal base class for app-level implementations. This service is intended to produce config
 * expected by its client-side partner and to process server-side token validations for use by
 * app-specific `AuthenticationService` calls. (See Toolbox for an example implementation.)
 */
@CompileStatic
abstract class BaseOauthService extends BaseService {

    /** Return OauthConfig used by browser Hoist React OauthService implementation. */
    abstract Map getClientConfig()

    /**
     * Validate a token received from the client, confirming its signature and extracting
     * a small set of standardized fields for user identification.
     */
    abstract TokenValidationResult validateToken(String token)

    protected Map getOauthConfig() {
        return Utils.configService.getMap('xhOauthConfig', emptyMap())
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhOauthConfig')
    ]}
}
