/**
 * IVR tree REST request and response contracts.
 *
 * <p>Exposed as a named interface because the {@code campaign} module's
 * create-IVR-from-campaign operation returns a tree and the shared HTTP error
 * contract has to be visible to it. Without this the dependency would still
 * exist but would be undeclared, which is exactly the kind of coupling Modulith
 * is there to make explicit.
 */
@org.springframework.modulith.NamedInterface("dto")
package com.shivang.obd.ivr.dto;
