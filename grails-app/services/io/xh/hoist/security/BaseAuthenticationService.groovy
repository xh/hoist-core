/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Abstract base service for processing and confirming user authentications and evaluating incoming
 * requests to determine if authentication is either complete or not required.
 *
 * Apps must define a concrete implementation of this service with the name 'AuthenticationService'.
 */
@CompileStatic
abstract class BaseAuthenticationService {

    IdentityService identityService

    //-----------------------------------
    // Core template methods for override
    //-----------------------------------
    /**
     * Perform authentication on this request.
     *
     * This method is called when a user starts a new session with the server.
     * Implementations should call setUser() to indicate recognition of an authenticated user.
     *
     * Returns boolean, indicating whether the authentication is complete.
     *
     * If true and a user has been set the request will continue with the authorized user.
     * If true and *no* user has been set the framework will throw a 401 Exception.
     *
     * If false returned no further action will be taken on the response.
     * This layer will be assumed to be in the middle of a redirect or protocol based negotiation.
     */
    abstract protected boolean completeAuthentication(HttpServletRequest request, HttpServletResponse response)


    /**
     * Process an interactive username + password based login. App implementations supporting
     * interactive login should return true if the user can be resolved and is authenticated.
     *
     * SSO-based implementations should leave this default implementation in place to indicate that
     * interactive login is not supported.
     */
    boolean login(HttpServletRequest request, String username, String password) {
        return false
    }


    /**
     * Take any app-specific actions to remove cached login information for this user and
     * require an explicit re-login. App implementations supporting interactive logout should return
     * true if logout was either successfully completed or required no further action.
     *
     * Note that this method is called by IdentityService, which will clear the authenticated user
     * from the session provided this method confirms logout from the application perspective.
     *
     * SSO-based implementations should leave this default implementation in place to indicate that
     * interactive logout is not supported.
     */
    boolean logout() {
        return false
    }


    //--------------------
    // Implemented methods
    //--------------------
    /**
     * Called once on every request to ensure request is authenticated before passing through to
     * rest of the framework. Not typically overridden - see completeAuthentication() as main entry
     * point for subclass implementation.
     */
    boolean allowRequest(HttpServletRequest request, HttpServletResponse response) {
        if (identityService.getAuthUser(request) || isWhitelist(request)) {
            return true
        }

        def complete = completeAuthentication(request, response)
        if (!complete) return false

        if (!identityService.getAuthUser(request)) {
            response.setStatus(401)
            response.flushBuffer()
            return false
        }

        return true
    }

    /**
     * Set the authenticated user, to be called by implementations of completeAuthentication()
     * when user has been reliably determined.
     */
    protected void setUser(HttpServletRequest request, HoistUser user) {
        identityService.noteUserAuthenticated(request, user)
    }

    /**
     * Identify requests that do not require an authenticated user. App AuthenticationServices can
     * whitelist additional requests, but should call this superclass implementation to ensure
     * required auth URIs are included.
     */
    protected boolean isWhitelist(HttpServletRequest request) {
        def uri = request.requestURI
        return whitelistURIs.any{uri.endsWith(it)} || isWhitelistFile(uri)
    }

    protected boolean isWhitelistFile(String uri) {
        whitelistFileExtensions.any{uri.endsWith(it)}
    }

    /**
     * Full URIs that should not require authentication. These are Hoist endpoints called in the
     * process of auth, or from which we wish to always return data.
     *
     * Note that this deliberately does *not* contain the authStatus check URI. We do not whitelist
     * that URI as otherwise SSO-based apps will not have a first shot at installing a user on the
     * session within their completeAuthentication() implementations.
     */
    protected List<String> whitelistURIs = [
        '/ping',
        '/xh/login',
        '/xh/logout',
        '/xh/environment',
        '/xh/version'
    ]

    /**
     * Extensions of file-based assets that do not require authentication and can be skipped for
     * efficiency. Note that for Hoist React applications, the Grails server typically neither
     * serves nor secures static assets, minimizing the impact of this list / need for tuning.
      */
    protected List<String> whitelistFileExtensions = [
        '.css',
        '.js',
        '.gif',
        '.ico',
        '.jpeg',
        '.jpg',
        '.png',
        '.svg',
        '.ttf',
        '.woff',
        '.woff2'
    ]

}
