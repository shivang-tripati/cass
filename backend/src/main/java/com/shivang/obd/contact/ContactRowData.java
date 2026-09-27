package com.shivang.obd.contact;

/**
 * A single parsed contact row from an import file. Attributes are kept
 * as their raw string form — JSON/object validity is a service-level,
 * per-row business rule so malformed values become row errors instead of
 * file failures.
 */
public record ContactRowData(
    int rowNumber,
    String phoneNumber,
    String firstName,
    String lastName,
    String email,
    String attributesJson
) {
}
