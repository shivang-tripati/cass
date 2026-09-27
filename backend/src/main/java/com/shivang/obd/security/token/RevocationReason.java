package com.shivang.obd.security.token;

/**
 * Stable internal revocation reasons. Only reasons actively used by shipped
 * phases are declared; future lifecycle features (password change, security
 * response, admin action, account suspension) add constants here without
 * any architectural change. Reasons are internal observability data and are
 * never exposed to API clients.
 */
public enum RevocationReason {
    LOGOUT,
    LOGOUT_ALL,
    TOKEN_REUSE,
    PASSWORD_CHANGED,
    SECURITY_RESPONSE
}
