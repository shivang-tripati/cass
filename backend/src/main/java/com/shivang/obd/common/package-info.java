/**
 * Shared kernel of the OBD platform: API contract, error model, audit
 * foundation and generic lifecycle types. Deliberately domain-independent
 * and open, so every business module may build on it — while the business
 * modules themselves stay closed and may not be reached from here.
 */
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.shivang.obd.common;
