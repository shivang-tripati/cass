package com.shivang.obd.contact.dto;

import java.util.List;

/**
 * Result of a bulk contact import. {@code totalRows} counts parsed data
 * rows; {@code skipped} aggregates duplicates and invalid rows;
 * {@code errors} lists every skipped row (duplicates included) capped at
 * a bounded size to keep responses small.
 */
public record ContactImportResponse(
    int totalRows,
    int created,
    int skipped,
    int duplicateCount,
    int errorCount,
    List<ContactImportError> errors
) {
}
