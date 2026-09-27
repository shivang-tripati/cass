package com.shivang.obd.telephony;

/**
 * Exception indicating an ESL (Event Socket Library) communication error.
 * <p>
 * Used for connection failures, authentication failures, command timeouts,
 * and FreeSWITCH-originated errors.
 */
public class EslException extends RuntimeException {

    public EslException(String message) {
        super(message);
    }

    public EslException(String message, Throwable cause) {
        super(message, cause);
    }
}