package com.shivang.obd.telephony;

/**
 * Phone list type hierarchy for call eligibility.
 * Precedence: PLATFORM_BLOCKLIST > PLATFORM_PROTECTED > RESELLER_BLOCKLIST > TENANT_BLOCKLIST > TENANT_WHITELIST
 * Higher priority list type wins when number appears in multiple lists.
 */
public enum PhoneListType {
    PLATFORM_BLOCKLIST,
    PLATFORM_PROTECTED,
    RESELLER_BLOCKLIST,
    RESELLER_WHITELIST,
    TENANT_BLOCKLIST,
    TENANT_WHITELIST
}