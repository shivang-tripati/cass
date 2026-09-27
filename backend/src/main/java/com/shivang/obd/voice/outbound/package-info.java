/**
 * VB-4E agent-originated outbound calling.
 * <p>
 * Named interface so the telephony event service can delegate outbound
 * customer-leg lifecycle events (answer/progress/hangup on
 * {@code CONTACT_CENTER_OUTBOUND} sessions, which carry no CallAttempt)
 * to {@code AgentOutboundCallService} without opening the package.
 */
@org.springframework.modulith.NamedInterface("outbound")
package com.shivang.obd.voice.outbound;
