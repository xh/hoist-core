package io.xh.hoist.user

interface IdentitySupport {

    /**
     * Return the current active user. Note that this is the 'apparent' user, used for most
     * application level purposes. In the case of an active impersonation session this will be
     * different from the authenticated user.
     *
     * If called outside the context of a request, this getter will return null.
     */
    HoistUser getUser()

    /**
     * Return the current active username. Note that this is the 'apparent' user, used for most
     * application level purposes. In the case of an active impersonation session this will be
     * different from the authenticated user.
     *
     * If called outside the context of a request, this getter will return null.
     */
    String getUsername()

    /**
     *  The 'authorized' user as verified by AuthenticationService.
     *
     *  If called outside the context of a request, this getter will return null.
     */
    HoistUser getAuthUser()

    /**
     * Return the username of the 'authorized' user as verified by AuthenticationService.
     *
     * If called outside the context of a request, this getter will return null.
     */
    String getAuthUsername()
}