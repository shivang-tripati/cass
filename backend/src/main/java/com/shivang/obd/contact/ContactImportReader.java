package com.shivang.obd.contact;

import java.io.InputStream;
import java.util.List;

/**
 * Internal format abstraction for bulk contact import. Implementations
 * translate a supported file into raw rows; ownership, authorization and
 * business validation never happen here. Selected by file extension.
 */
public interface ContactImportReader {

    boolean supports(String filename);

    /**
     * Reads every data row. The first record must carry the header; the
     * {@code phoneNumber} column is mandatory (missing header → 400).
     *
     * @param rowNumber human-friendly 1-based row number reported back to
     *                  callers in import errors
     */
    List<ContactRowData> read(InputStream inputStream);
}
