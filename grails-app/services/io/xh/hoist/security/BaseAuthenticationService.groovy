/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Applications must define a concrete implementation of this service with the name 'AuthenticationService'
 */
@CompileStatic
abstract class BaseAuthenticationService {

    IdentityService identityService

    /**
     * Remove any cached login information for this user, requiring explicit re-login.
     * Some SSO-based implementations may be unable to do this, in which case an exception should be thrown.
     */
    void logout() {}

    /**
     * Call once on every request to ensure request is authenticated before passing through to rest of the framework.
     * Not typically overridden. See completeAuthentication() for main entry point for implementing subclasses.
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
     * If true returned and a user has been set via setUser() the request will continue with the authorized user.
     * If true and *no* user has been set the framework will throw a 401 Exception.
     *
     * If false returned no further action will be taken on the response.
     * This layer will be assumed to be in the middle of a redirect or protocol based negotiation.
     */
    abstract protected boolean completeAuthentication(HttpServletRequest request, HttpServletResponse response)


    //--------------------
    // Implemented methods
    //--------------------
    /**
     * Enumerate requests that do not require an authenticated user.
     * Should be implemented in AuthenticationService and called with super to add app specific whitelisted requests
     */
    protected boolean isWhitelist(HttpServletRequest request) {
        return request.requestURI == '/hoistImpl/version'
    }

    /**
     * Set the authenticated user.
     * Should be called by implementations of completeAuthentication() when user has been reliably determined.
     */
    protected void setUser(HttpServletRequest request, HoistUser user) {
        identityService.noteUserAuthenticated(request, user)
    }

}
