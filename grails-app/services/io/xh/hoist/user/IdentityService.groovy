/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.security.BaseAuthenticationService
import io.xh.hoist.track.TrackService
import org.grails.web.util.WebUtils

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpSession

/**
 * Primary service for retrieving the logged-in HoistUser (aka the application user), with support for impersonation.
 * This service powers the getUser() and getUsername() methods in Hoist's BaseService and BaseController classes.
 */
@CompileStatic
class IdentityService extends BaseService {

    static public String AUTH_USER_KEY = 'xhAuthUser'
    static public String APPARENT_USER_KEY = 'xhApparentUser'
    
    BaseUserService userService
    BaseAuthenticationService authenticationService
    TrackService trackService


    //-----------------------------------
    // Entry points for applications
    //-----------------------------------
    /**
     * Return the current active user. Note that this is the 'apparent' user, used for most application level purposes.
     * In particular, in the case of impersonation this may be different from the authenticated user.
     */
    HoistUser getUser() {
        getApparentUser()
    }

    /**
     *  The 'apparent' user, used for most application level purposes.
     */
    HoistUser getApparentUser(HttpServletRequest request = getRequest()) {
        def session = getSessionIfExists(request)
        return session ? (HoistUser) session[APPARENT_USER_KEY] : null
    }

    /**
     *  The 'authorized' user as verified by AuthenticationService.
     */
    HoistUser getAuthUser(HttpServletRequest request = getRequest()) {
        def session = getSessionIfExists(request)
        return session ? (HoistUser) session[AUTH_USER_KEY] : null
    }

    /**
     * Is the authorized user currently impersonating someone else?
     */
    boolean isImpersonating() {
        def request = getRequest(),
            apparentUser = getApparentUser(request),
            authUser = getAuthUser(request)

        return apparentUser != authUser
    }

    /**
     * Begin an impersonation session - callable only when current (actual) user has the HOIST_ADMIN role.
     * During an impersonation session, all calls to getUser() will return the impersonated user and appear
     * as if that user is the one browsing the app. Permissions, preferences, and other user-specific settings
     * will be loaded for the impersonated user to facilitate app testing and troubleshooting.
     *
     * @param username - the user to impersonate
     */
    HoistUser impersonate(String username) {
        def request = getRequest(),
            targetUser = userService.find(username),
            authUser = getAuthUser(request)

        if (!authUser.roles.contains('HOIST_ADMIN')) {
            throw new RuntimeException("User '${authUser.username}' does not have permissions to impersonate")
        }
        if (!targetUser?.active) {
            throw new RuntimeException("Cannot impersonate '$username'.  No active user found")
        }

        trackImpersonate("User '${authUser.username}' is now impersonating user '${targetUser.username}'")
        request.session[APPARENT_USER_KEY] = targetUser
        return targetUser
    }

    /**
     * End the active impersonation session, if any, reverting to the current user browsing the app as themselves.
     */
    void endImpersonate() {
        def request = getRequest(),
            apparentUser = getApparentUser(request),
            authUser = getAuthUser(request)

        if (apparentUser != authUser) {
            request.session[APPARENT_USER_KEY] = authUser
            trackImpersonate("User '${authUser.username}' has stopped impersonating user '${apparentUser.username}'")
        }
    }

    /**
     * Return minimal user information required by a client-side web app for identity management.
     */
    Map getClientConfig() {
        def request = getRequest(),
            apparentUser = getApparentUser(request),
            authUser = getAuthUser(request)

        return authUser != apparentUser ?   // Don't duplicate serialization
                [apparentUser: apparentUser, authUser: authUser] :
                [user: authUser]
    }

    /**
     * Remove any login information for this user.
     *
     * Some SSO-based implementations of authenticationService may be unable to do this.
     * In this case an exception may be thrown.
     */
    void logout() {
        authenticationService.logout()
        def session = getSessionIfExists()
        if (session) {
            session[APPARENT_USER_KEY] = session[AUTH_USER_KEY] = null
        }
    }


    //----------------------------------------
    // Entry Point for AuthenticationService
    //----------------------------------------
    /**
     * Called by BaseAuthentication Service when User has first been reliably established for this session.
     */
    void noteUserAuthenticated(HttpServletRequest request, HoistUser user) {
        def session = request.session
        session[APPARENT_USER_KEY] = session[AUTH_USER_KEY] = user
    }


    //----------------------
    // Implementation
    //----------------------
    private HttpServletRequest getRequest() {
        def req = WebUtils.retrieveGrailsWebRequest()
        if (!req) {
            throw new RuntimeException('Attempting to get user information outside of valid request')
        }
        return req.getRequest()
    }

    private void trackImpersonate(String msg) {
        trackService.track(category: 'Impersonate', message: msg, severity: 'WARN')
    }

    private HttpSession getSessionIfExists(HttpServletRequest request = getRequest()) {
        return request.getSession(false)  // Do *not* create session for simple, early checks (avoid DOS attack)
    }

}