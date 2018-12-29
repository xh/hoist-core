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
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.springframework.web.context.request.RequestContextHolder

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpSession

/**
 * Primary service for retrieving the logged-in HoistUser (aka the application user), with support
 * for impersonation. This service powers the getUser() and getUsername() methods in Hoist's
 * BaseService and BaseController classes.
 *
 * The implementation of this service uses the session to hold the authenticated username (and
 * potentially a distinct "apparent" username when impersonation is active). It depends on the app's
 * AuthenticationService to initialize the authenticated user via noteUserAuthenticated(), and
 * likewise delegates to the app's UserService to resolve usernames to actual HoistUser objects.
 *
 * This service is *not* intended for override or customization at the application level.
 */
@CompileStatic
class IdentityService extends BaseService {

    static public String AUTH_USER_KEY = 'xhAuthUser'
    static public String APPARENT_USER_KEY = 'xhApparentUser'
    
    BaseAuthenticationService authenticationService
    BaseUserService userService
    TrackService trackService


    /**
     * Return the current active user. Note that this is the 'apparent' user, used for most
     * application level purposes. In the case of an active impersonation session this will be
     * different from the authenticated user.
     *
     * If called outside the context of a request, this getter will return null.
     */
    HoistUser getUser() {
        getApparentUser()
    }

    /**
     *  The 'apparent' user, used for most application level purposes.
     *
     *  If called outside the context of a request, this getter will return null.
     */
    HoistUser getApparentUser(HttpServletRequest request = getRequest()) {
        return findHoistUserViaSessionKey(request, APPARENT_USER_KEY)
    }

    /**
     *  The 'authorized' user as verified by AuthenticationService.
     *
     *  If called outside the context of a request, this getter will return null.
     */
    HoistUser getAuthUser(HttpServletRequest request = getRequest()) {
        return findHoistUserViaSessionKey(request, AUTH_USER_KEY)
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

        if (!request) {
            throw new RuntimeException('Cannot impersonate when outside the context of a request')
        }
        
        if (!authUser.isHoistAdmin) {
            throw new RuntimeException("User '${authUser.username}' does not have permissions to impersonate")
        }
        if (!targetUser?.active) {
            throw new RuntimeException("Cannot impersonate '$username' - no active user found")
        }

        trackImpersonate("User '${authUser.username}' is now impersonating user '${targetUser.username}'")
        request.session[APPARENT_USER_KEY] = targetUser.username
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
     * Return a list of users available for impersonation.
     */
    List<HoistUser> getImpersonationTargets() {
        getAuthUser().isHoistAdmin ? userService.list(true) : new ArrayList<HoistUser>()
    }

    /**
     * Return minimal identify information for confirmed authenticated users.
     * Used by client-side web app for identity management.
     */
    Map getClientConfig() {
        def request = getRequest(),
            apparentUser = getApparentUser(request),
            authUser = getAuthUser(request)

        return (authUser != apparentUser) ?
            [
                apparentUser: apparentUser,
                apparentUserRoles: apparentUser.roles,
                authUser: authUser,
                authUserRoles: authUser.roles,
            ] :
            [
                user: authUser,
                roles: authUser.roles
            ]
    }

    /**
     * Process an interactive username + password based login.
     * Note SSO-based implementations of authenticationService will always return false.
     */
    boolean login(String username, String password) {
        return authenticationService.login(request, username, password)
    }

    /**
     * Remove any login information for this user.
     * Note SSO-based implementations of authenticationService will always return false.
     */
    boolean logout() {
        if (authenticationService.logout()) {
            def session = getSessionIfExists()
            if (session) session[APPARENT_USER_KEY] = session[AUTH_USER_KEY] = null
            return true
        }

        return false
    }

    /**
     * Entry Point for AuthenticationService
     * Called by authenticationService when HoistUser has first been established for this session.
     */
    void noteUserAuthenticated(HttpServletRequest request, HoistUser user) {
        def session = request.session
        session[APPARENT_USER_KEY] = session[AUTH_USER_KEY] = user.username
    }

    //----------------------
    // Implementation
    //----------------------
    private HttpServletRequest getRequest() {
        def attr = RequestContextHolder.requestAttributes

        // If we are not in the context of a request (e.g. service timer) this will return null.
        return (attr && attr instanceof GrailsWebRequest) ?
                ((GrailsWebRequest)attr).request:
                null
    }

    private void trackImpersonate(String msg) {
        trackService.track(category: 'Impersonate', msg: msg, severity: 'WARN')
    }

    private HoistUser findHoistUserViaSessionKey(HttpServletRequest request, String key) {
        HttpSession session = getSessionIfExists(request)
        String username = session ? session[key] : null
        return username ? userService.find(username) : null
    }

    private HttpSession getSessionIfExists(HttpServletRequest request = getRequest()) {
        return request?.getSession(false)  // Do *not* create session for simple, early checks (avoid DOS attack)
    }

}