package com.shivang.obd.security.event;

public enum SecurityEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    LOGOUT_ALL,
    TOKEN_REFRESH,
    TOKEN_REUSE_DETECTED,
    PASSWORD_CHANGED
}
