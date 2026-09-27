package com.shivang.obd.contact;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON reader expecting an array of contact objects with the same field
 * names as the CSV columns. Nested {@code attributes} objects are kept
 * as raw JSON strings for service-level validation.
 */
@Component
public class JsonContactImportReader implements ContactImportReader {

    private final ObjectMapper objectMapper;

    public JsonContactImportReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    @Override
    public List<ContactRowData> read(InputStream inputStream) {
        JsonNode root;
        try {
            root = objectMapper.readTree(inputStream);
        } catch (JacksonException ex) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Malformed JSON file.");
        }
        if (root == null || !root.isArray()) {
            throw new BusinessException(
                CommonErrorCode.VALIDATION_ERROR, "JSON payload must be an array of contact objects.");
        }

        List<ContactRowData> rows = new ArrayList<>(root.size());
        int rowNumber = 1;
        for (JsonNode element : root) {
            if (!element.isObject()) {
                throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "Malformed JSON file: entry " + rowNumber + " is not an object.");
            }
            rows.add(new ContactRowData(
                rowNumber++,
                textOrNull(element, "phoneNumber"),
                textOrNull(element, "firstName"),
                textOrNull(element, "lastName"),
                textOrNull(element, "email"),
                rawAttributes(element)));
        }
        return rows;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText().trim();
    }

    private String rawAttributes(JsonNode node) {
        JsonNode attributes = node.path("attributes");
        if (attributes.isMissingNode() || attributes.isNull()) {
            return null;
        }
        return objectMapper.writeValueAsString(attributes);
    }
}
