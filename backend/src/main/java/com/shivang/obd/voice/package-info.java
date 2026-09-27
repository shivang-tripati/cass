/**
 * Shared Voice Core module.
 * <p>
 * Provides universal voice lifecycle abstractions (CallSession, CallLeg) and
 * voice-layer services (eligibility, routing, capacity, endpoint, media)
 * reusable by Voice Blast, Contact Center, and AI products.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Voice Core Module"
)
package com.shivang.obd.voice;