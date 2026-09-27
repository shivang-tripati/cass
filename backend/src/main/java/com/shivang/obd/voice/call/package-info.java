/**
 * Universal call session/leg lifecycle owned by the voice core.
 * <p>
 * Named interface: campaign orchestration and telephony event handling
 * consume {@code CallSession}/{@code CallLeg} and their repositories as the
 * canonical voice API; other packages of this module stay internal.
 */
@org.springframework.modulith.NamedInterface("call")
package com.shivang.obd.voice.call;
