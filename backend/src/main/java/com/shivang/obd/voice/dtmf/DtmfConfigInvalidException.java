package com.shivang.obd.voice.dtmf;

/**
 * Thrown when a campaign's {@code type_config} does not carry a valid
 * {@code dtmf} payload. Mapped to the permanent failure code
 * {@code DTMF_CONFIG_INVALID} — retrying a misconfigured campaign can
 * never succeed.
 */
public class DtmfConfigInvalidException extends RuntimeException {
    public DtmfConfigInvalidException(String message) {
        super(message);
    }
}
