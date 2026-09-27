/**
 * Reseller module. Owns the reseller aggregate, reseller memberships and
 * reseller provisioning/self-signup. Organizational boundaries are always
 * server-derived from the authenticated principal's organizational home.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Reseller Module"
)
package com.shivang.obd.reseller;
