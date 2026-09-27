package com.shivang.obd.tts;

/**
 * One declared template variable. Simple named placeholders only — no
 * expressions, no scripting. Persisted as part of the JSONB variable
 * schema array.
 */
public record TtsTemplateVariable(
    String name,
    String type,
    Boolean required
) {
}
