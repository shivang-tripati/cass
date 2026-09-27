/**
 * Inbound calling API of the voice module (VB-4D).
 * <p>
 * Exposes the {@link com.shivang.obd.voice.inbound.InboundCallEvents}
 * boundary consumed by the telephony event service. The implementation
 * ({@code InboundCallService}) stays encapsulated; no other module may
 * reach into this package beyond the named interface.
 */
@org.springframework.modulith.NamedInterface("inbound")
package com.shivang.obd.voice.inbound;
