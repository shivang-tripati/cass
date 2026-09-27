package com.shivang.obd.common.exception;

import com.shivang.obd.common.api.error.CommonErrorCode;

/**
 * Thrown when an authenticated user lacks the required capability or scope
 * for the requested operation. Produces a 403 ProblemDetail with a generic
 * message that does not leak resource ownership, tenant identity, or role
 * information.
 */
public class ForbiddenException extends BusinessException {

    public static final String GENERIC_MESSAGE = "Access denied.";

    public ForbiddenException() {
        super(CommonErrorCode.FORBIDDEN, GENERIC_MESSAGE);
    }

    public ForbiddenException(String message) {
        super(CommonErrorCode.FORBIDDEN, message);
    }
}
