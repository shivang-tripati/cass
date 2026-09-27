/**
 * Voice capacity (channel/CPS) management SPI.
 * <p>
 * Named interface: campaign dialing and voice routing consume
 * {@code VoiceCapacityService} as the canonical voice capacity API;
 * the advisory-lock implementation lives behind the SPI.
 */
@org.springframework.modulith.NamedInterface("capacity")
package com.shivang.obd.voice.capacity;
