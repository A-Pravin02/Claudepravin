package com.aea.platform;

import org.springframework.http.HttpStatus;

/**
 * An error with a status and a message that is safe to return to the caller.
 *
 * SECURITY: the message is user-facing, so it must never disclose whether a
 * record exists, which tenant owns it, or why exactly a policy denied access.
 * The real reason belongs in the audit log. See ApiExceptionHandler.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String safeMessage) {
        super(safeMessage);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException unauthorized() {
        // Deliberately identical for "no such user" and "wrong password":
        // distinguishing them turns the login form into a user enumerator.
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN,
                "This request requires permissions your account does not have.");
    }
}
