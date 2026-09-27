package com.shivang.obd.contact;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * CSV reader (RFC-4180 via Apache Commons CSV). First record is the
 * header; the phoneNumber column is mandatory.
 */
@Component
public class CsvContactImportReader implements ContactImportReader {

    private static final String PHONE_COLUMN = "phonenumber";

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    @Override
    public List<ContactRowData> read(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                 .setIgnoreEmptyLines(true)
                 .build()
                 .parse(reader)) {

            var records = parser.iterator();
            if (!records.hasNext()) {
                throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR, "Missing required column: phoneNumber");
            }
            // The first record is the header; Commons CSV does not parse
            // it as one unless the format declares a header, so read it
            // explicitly and index its columns case-insensitively.
            Map<String, Integer> columns = headerIndex(records.next());
            if (!columns.containsKey(key("phoneNumber"))) {
                throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR, "Missing required column: phoneNumber");
            }

            List<ContactRowData> rows = new ArrayList<>();
            while (records.hasNext()) {
                CSVRecord record = records.next();
                rows.add(new ContactRowData(
                    (int) record.getRecordNumber() + 1, // +1 accounts for the header line
                    value(record, columns, "phoneNumber"),
                    value(record, columns, "firstName"),
                    value(record, columns, "lastName"),
                    value(record, columns, "email"),
                    value(record, columns, "attributes")));
            }
            return rows;
        } catch (IOException ex) {
            throw new BusinessException(
                CommonErrorCode.VALIDATION_ERROR, "Malformed CSV file: " + ex.getMessage());
        }
    }

    private static final String UTF8_BOM = "\uFEFF";

    private static String key(String column) {
        // Tolerate the UTF-8 BOM that tools like Excel prepend to the
        // first header cell.
        String normalized = column.startsWith(UTF8_BOM)
            ? column.substring(UTF8_BOM.length())
            : column;
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Integer> headerIndex(CSVRecord headerRecord) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < headerRecord.size(); i++) {
            String rawCell = headerRecord.get(i);
            String name = rawCell.startsWith(UTF8_BOM)
                ? key(rawCell.substring(1))
                : key(rawCell);
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, i);
            }
        }
        return columns;
    }

    private static String value(CSVRecord record, Map<String, Integer> columns, String column) {
        Integer index = columns.get(key(column));
        if (index == null || index >= record.size()) {
            return null;
        }
        String raw = record.get(index);
        return raw == null ? null : raw.trim();
    }
}
