/**
 * Voice media control (dialing, playback, DTMF collection) SPI.
 * <p>
 * Named interface: campaign execution services consume
 * {@code OutboundDialer}, {@code VoiceMediaController}, playback/DTMF
 * trigger SPIs and their request/response values as the canonical voice
 * media API; FreeSWITCH adapters implement them in the telephony module.
 */
@org.springframework.modulith.NamedInterface("media")
package com.shivang.obd.voice.media;
