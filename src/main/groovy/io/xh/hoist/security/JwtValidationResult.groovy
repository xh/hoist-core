package io.xh.hoist.security

import io.xh.hoist.json.JSONFormat

class JwtValidationResult implements JSONFormat {

    /** The OauthProvider-issued JWT that was validated. */
    final String idToken

    /** True if the token was determined to be valid and should be trusted. */
    boolean isValid

    /** User info extracted from token payload. */
    final String sub
    final String email


    /** Exception (if any) encountered while attempting to validate the token. */
    final Exception exception
    /** Date token was validated by app. */
    final Date dateCreated

    JwtValidationResult(Map mp) {
        idToken = mp.idToken
        sub = mp.sub
        email = mp.email?.toLowerCase()
        exception = mp.exception as Exception
        dateCreated = new Date()
    }

    Map formatForJSON() {
        return [
            idToken: idToken,
            isValid: isValid,
            sub: sub,
            email: email,
            exception: exception,
            dateCreated: dateCreated
        ]
    }
}
