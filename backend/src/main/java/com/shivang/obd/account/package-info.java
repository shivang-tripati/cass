/**
 * Account module. User management (read/update) within server-derived
 * organizational boundaries. Account CREATION lives in the provisioning
 * flows of the reseller/tenant modules and the identity bootstrap, never
 * here.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Account Module"
)
package com.shivang.obd.account;
