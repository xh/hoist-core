package io.xh.hoist.security.oauth

import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessAll
import io.xh.hoist.util.Utils

/**
 * Controller, accessible pre-auth via AuthenticationService whitelist, to allow soft-config of
 * Auth0/Oauth related settings on the client.
 */
@AccessAll
class OauthConfigController extends BaseController {

    def index() {
        renderJSON(oauthService.clientConfig)
    }


    private BaseOauthService getOauthService() {
        return Utils.appContext.getBean(BaseOauthService)
    }
}
