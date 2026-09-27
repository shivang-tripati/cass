package com.shivang.obd.contact.dto;

/** One row-level import failure or skip, reported with its file row number. */
public record ContactImportError(
    int rowNumber,
    String field,
    String code,
    String message
) {
}
