package io.xh.hoist.security.oauth

import io.xh.hoist.json.JSONFormat

class TokenValidationResult implements JSONFormat {

    /** The OauthProvider-issued JWT that was validated. */
    final String idToken

    /** True if the token was determined to be valid and should be trusted. */
    boolean isValid

    /** User info extracted from token payload. */
    final String username
    final String sub

    /** Exception (if any) encountered while attempting to validate the token. */
    final Exception exception
    /** Date token was validated by app. */
    final Date dateCreated

    TokenValidationResult(Map mp) {
        idToken = mp.idToken
        username = mp.username
        sub = mp.sub
        exception = mp.exception as Exception
        isValid = sub && username && !exception
        dateCreated = new Date()
    }

    Map formatForJSON() {
        return [
            idToken: idToken,
            isValid: isValid,
            sub: sub,
            username: username,
            exception: exception,
            dateCreated: dateCreated
        ]
    }
}
