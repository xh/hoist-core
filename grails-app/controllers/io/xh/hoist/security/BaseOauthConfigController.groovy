package io.xh.hoist.security

import io.xh.hoist.BaseController

/**
 * Controller, accessible pre-auth via AuthenticationService whitelist, to allow soft-config of
 * Auth0/Oauth related settings on the client.
 */
@AccessAll
abstract class BaseOauthConfigController extends BaseController {

    def index() {
        renderJSON(oauthService.clientConfig)
    }

    //--------------------------------
    // Template Methods for Override
    //--------------------------------

    protected BaseOauthService getOauthService() {
        return oauthService
    }
}
