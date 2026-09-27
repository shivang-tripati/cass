package com.shivang.obd.voice.dtmf;

/**
 * Result of a DTMF collection interaction (VB-2).
 * <p>
 * Mirrors the {@code dtmf_result_type} PostgreSQL enum (V35) — the column is
 * bound with {@code NAMED_ENUM}, so this name set must match the migration.
 * <ul>
 *   <li>{@link #COLLECTING} — interaction in progress (non-terminal)</li>
 *   <li>{@link #VALID} — collected input matches the expected sequence</li>
 *   <li>{@link #INVALID} — collected input definitively cannot match
 *       (wrong digit / exceeded max digits / terminator not allowed)</li>
 *   <li>{@link #TIMEOUT} — no or insufficient input before the deadline</li>
 *   <li>{@link #ABANDONED} — call ended (remote hangup) before completion</li>
 * </ul>
 * {@code INVALID}, {@code TIMEOUT} and {@code ABANDONED} are USER INPUT
 * outcomes, not technical failures: they never retry the call.
 */
public enum DtmfResultType {
    COLLECTING,
    VALID,
    INVALID,
    TIMEOUT,
    ABANDONED
}
