package com.shivang.obd.contact;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * XLSX reader via Apache POI. First sheet, first row is the header; the
 * phoneNumber column is mandatory. Cell values are read as formatted
 * text so numbers/strings behave like their CSV counterparts.
 */
@Component
public class XlsxContactImportReader implements ContactImportReader {

    private static final DataFormatter FORMATTER = new DataFormatter();

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    @Override
    public List<ContactRowData> read(InputStream inputStream) {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR, "Missing required column: phoneNumber");
            }
            Map<String, Integer> columns = headerIndex(headerRow);
            if (!columns.containsKey("phonenumber")) {
                throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR, "Missing required column: phoneNumber");
            }

            List<ContactRowData> rows = new ArrayList<>();
            for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankRow(row, columns)) {
                    continue;
                }
                rows.add(new ContactRowData(
                    r + 1,
                    text(row, columns, "phoneNumber"),
                    text(row, columns, "firstName"),
                    text(row, columns, "lastName"),
                    text(row, columns, "email"),
                    text(row, columns, "attributes")));
            }
            return rows;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            // Corrupt/unreadable workbooks are a client input problem and must surface as 400.
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Malformed XLSX file.");
        }
    }

    private static Map<String, Integer> headerIndex(Row headerRow) {
        Map<String, Integer> columns = new HashMap<>();
        for (Cell cell : headerRow) {
            String name = FORMATTER.formatCellValue(cell).trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, cell.getColumnIndex());
            }
        }
        return columns;
    }

    private static boolean isBlankRow(Row row, Map<String, Integer> columns) {
        Integer phoneIndex = columns.get(key("phoneNumber"));
        return phoneIndex == null || FORMATTER.formatCellValue(row.getCell(phoneIndex)).isBlank();
    }

    /** Column names are matched case-insensitively against the header row. */
    private static String key(String column) {
        return column.toLowerCase(Locale.ROOT);
    }

    private static String text(Row row, Map<String, Integer> columns, String field) {
        Integer index = columns.get(key(field));
        if (index == null) {
            return null;
        }
        String value = FORMATTER.formatCellValue(row.getCell(index)).trim();
        return value.isEmpty() ? null : value;
    }
}
