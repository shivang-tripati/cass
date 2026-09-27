
```sql
package com.voxbridge.core.exception;

public enum ErrorCode {
    // Authentication & Authorization
    INVALID_CREDENTIALS("AUTH_001"),
    TOKEN_EXPIRED("AUTH_002"),
    INVALID_TOKEN("AUTH_003"),
    ACCESS_DENIED("AUTH_004"),

    // User
    USER_NOT_FOUND("USER_001"),
    USER_ALREADY_EXISTS("USER_002"),
    USER_INACTIVE("USER_003"),

    // Campaign
    CAMPAIGN_NOT_FOUND("CAMP_001"),
    CAMPAIGN_INVALID_STATE("CAMP_002"),
    CAMPAIGN_ALREADY_RUNNING("CAMP_003"),

    // Lead
    LEAD_NOT_FOUND("LEAD_001"),
    LEAD_ALREADY_DIALED("LEAD_002"),
    LEAD_DNC("LEAD_003"),

    // Call
    CALL_ORIGINATION_FAILED("CALL_001"),
    CALL_ALREADY_ACTIVE("CALL_002"),
    CALL_NOT_FOUND("CALL_003"),

    // Compliance
    NDNC_BLOCKED("COMP_001"),
    TIME_RESTRICTION("COMP_002"),
    CONSENT_EXPIRED("COMP_003"),

    // System
    INTERNAL_ERROR("SYS_001"),
    SERVICE_UNAVAILABLE("SYS_002"),
    RATE_LIMIT_EXCEEDED("SYS_003"),
    INVALID_REQUEST("SYS_004");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```