/**
 * Tenant module. Owns the tenant aggregate, tenant memberships, tenant
 * provisioning (direct and under a reseller) plus SUPER_ADMIN-restricted
 * AGENT account creation. All organizational boundaries are server-derived.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Tenant Module"
)
package com.shivang.obd.tenant;
