package com.shivang.obd.campaign;

/**
 * Thrown when a Voice Blast execution has no valid, non-blank IANA
 * timezone in its immutable configuration snapshot (VB-6C.1).
 * <p>
 * The daily-limit policy is calendar-day based; without an authoritative
 * timezone the {@code usageDate} of the bucket is undefined. The
 * implementation deliberately does NOT fall back to the JVM default or
 * UTC: an ambiguous day boundary is a compliance hazard (calls could be
 * silently admitted on the wrong side of a limit reset), so execution
 * readiness fails deterministically instead (audit §2).
 */
public class ExecutionTimezoneInvalidException extends RuntimeException {

    public ExecutionTimezoneInvalidException(String message) {
        super(message);
    }
}
