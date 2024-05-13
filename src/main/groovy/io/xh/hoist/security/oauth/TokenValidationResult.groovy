package io.xh.hoist.security.oauth

import io.xh.hoist.json.JSONFormat

/**
 * Return from `OauthService.validateToken` API, encapsulating the result of JWT validation.
 *
 * Application implementations are expected to use a suitable library (e.g. jose4j) to actually
 * perform token validation and payload decoding. Apps are encouraged to extend this class to
 * include additional fields as needed, based on the contents of their particular tokens.
 */
class TokenValidationResult implements JSONFormat {

    /** The OauthProvider-issued JWT that was validated. */
    final String token

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
        token = mp.token
        username = mp.username
        sub = mp.sub
        exception = mp.exception as Exception
        isValid = sub && username && !exception
        dateCreated = new Date()
    }

    Map formatForJSON() {
        return [
            token: token,
            isValid: isValid,
            sub: sub,
            username: username,
            exception: exception,
            dateCreated: dateCreated
        ]
    }
}
