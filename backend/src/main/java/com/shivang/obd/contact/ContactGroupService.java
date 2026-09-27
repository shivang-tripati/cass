package com.shivang.obd.contact;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.PaginationMetadata;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.contact.dto.ContactGroupResponse;
import com.shivang.obd.contact.dto.ContactImportError;
import com.shivang.obd.contact.dto.ContactImportResponse;
import com.shivang.obd.contact.dto.ContactResponse;
import com.shivang.obd.contact.dto.CreateContactGroupRequest;
import com.shivang.obd.contact.dto.CreateContactRequest;
import com.shivang.obd.contact.dto.UpdateContactGroupRequest;
import com.shivang.obd.contact.dto.UpdateContactRequest;
import com.shivang.obd.security.CurrentUserProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.dao.DataIntegrityViolationException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contact Group / Contact application service. Authorization is enforced
 * at this boundary from the server-derived organizational context
 * (shared {@link ContactGroupAccess} gate). Groups and contacts are
 * tenant-owned; reseller visibility follows the active-tenant hierarchy,
 * platform scope is unbounded. Scoped lookups make foreign and
 * nonexistent resources indistinguishable (404).
 *
 * <p>VB-6B.2: all membership mutations (upserts and cascades) are
 * delegated to {@link ContactGroupMemberService} — this service no
 * longer touches membership rows directly; group and contact listings
 * carry the live member count via one grouped query.</p>
 */
@Service
@RequiredArgsConstructor
public class ContactGroupService {

    private static final String CAP_VIEW = "CONTACT_VIEW";
    private static final String CAP_MANAGE = "CONTACT_MANAGE";

    private static final List<String> GROUP_SORTABLE_FIELDS =
        List.of("name", "createdAt", "updatedAt");
    private static final List<String> CONTACT_SORTABLE_FIELDS =
        List.of("firstName", "phoneNumber", "createdAt");
    private static final Sort DEFAULT_GROUP_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final Sort DEFAULT_CONTACT_SORT = Sort.by(Sort.Direction.ASC, "firstName");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_IMPORT_ROWS = 5000;
    private static final int MAX_REPORTED_ERRORS = 100;
    private static final List<String> EXPORT_COLUMNS =
        List.of("phoneNumber", "firstName", "lastName", "email", "attributes");

    private final ContactGroupRepository groupRepository;
    private final ContactRepository contactRepository;
    /** Canonical membership boundary (VB-6B.2): upserts + cascades live here. */
    private final ContactGroupMemberService memberService;
    /** Contact identity boundary: canonicalization + typed dedup (VB-6B.1). */
    private final ContactIdentityService contactIdentityService;
    private final ContactGroupAccess groupAccess;
    private final ContactGroupMapper groupMapper;
    private final ContactMapper contactMapper;
    private final List<ContactImportReader> importReaders;
    private final ObjectMapper objectMapper;

    // === groups ===

    @Transactional
    public ApiResponse<ContactGroupResponse> createGroup(CreateContactGroupRequest request) {
        UUID userId = groupAccess.requireUserId();
        ContactGroupAccess.Scope scope = groupAccess.currentScope();
        UUID tenantId = scope.tenantId();
        if (tenantId == null) {
            throw business("A tenant must be specified for this operation.");
        }
        groupAccess.requireCapability(userId, CAP_MANAGE, com.shivang.obd.authz.AccessCheck.forTenant(tenantId));

        ContactGroupEntity saved = groupRepository.save(groupMapper.toEntity(request, tenantId));
        return ResponseFactory.created(withMemberCount(groupMapper.toResponse(saved), 0L));
    }

    @Transactional(readOnly = true)
    public ApiResponse<ContactGroupResponse> getGroup(UUID groupId) {
        ContactGroupEntity entity = groupAccess.authorizedGroup(groupId, CAP_VIEW);
        return ResponseFactory.ok(
            withMemberCount(groupMapper.toResponse(entity), memberService.memberCount(groupId)));
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<ContactGroupResponse>> listGroups(
        int page, int size, String[] sortArr, String search
    ) {
        UUID userId = groupAccess.requireUserId();
        ContactGroupAccess.Scope scope = groupAccess.currentScope();

        Specification<ContactGroupEntity> boundary;
        if (scope.tenantId() != null) {
            groupAccess.requireCapability(userId, CAP_VIEW, com.shivang.obd.authz.AccessCheck.forTenant(scope.tenantId()));
            boundary = ContactGroupSpecifications.forTenant(scope.tenantId());
        } else if (scope.resellerId() != null) {
            groupAccess.requireCapability(userId, CAP_VIEW, com.shivang.obd.authz.AccessCheck.forReseller(scope.resellerId()));
            List<UUID> hierarchyTenants = groupAccess.hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                return ResponseFactory.page(List.of(), PaginationMetadata.of(page, size, 0));
            }
            boundary = ContactGroupSpecifications.forTenants(hierarchyTenants);
        } else {
            groupAccess.requireCapability(userId, CAP_VIEW, com.shivang.obd.authz.AccessCheck.platformWide());
            boundary = null;
        }

        Specification<ContactGroupEntity> specification = ContactGroupSpecifications.compose(
            ContactGroupSpecifications.notDeleted(),
            boundary,
            ContactGroupSpecifications.search(search));

        Page<ContactGroupEntity> resultPage =
            groupRepository.findAll(specification, buildPageable(page, size, sortArr, DEFAULT_GROUP_SORT, GROUP_SORTABLE_FIELDS));
        List<ContactGroupResponse> items = withMemberCounts(resultPage.getContent()
            .stream().map(groupMapper::toResponse).toList());
        return ResponseFactory.page(items, PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    @Transactional
    public ApiResponse<ContactGroupResponse> updateGroup(UUID groupId, UpdateContactGroupRequest request) {
        ContactGroupEntity entity = groupAccess.authorizedGroup(groupId, CAP_MANAGE);

        groupMapper.updateEntity(entity, request);
        ContactGroupEntity saved = groupRepository.save(entity);
        return ResponseFactory.ok(
            withMemberCount(groupMapper.toResponse(saved), memberService.memberCount(groupId)));
    }

    /**
     * Soft-deletes a group (VB-6B.1): membership rows are physical
     * relationship rows and are removed with the group (via the canonical
     * membership service); contacts are identities and are never touched.
     * A group with live members can be deleted — the membership links are
     * what die, not the identities.
     */
    @Transactional
    public void deleteGroup(UUID groupId) {
        ContactGroupEntity entity = groupAccess.authorizedGroup(groupId, CAP_MANAGE);

        memberService.removeAllForGroup(groupId);

        Instant now = Instant.now();
        entity.setDeletedAt(now);
        entity.setDeletedBy(groupAccess.requireUserId().toString());
        groupRepository.save(entity);
    }

    // === contacts ===

    /**
     * Adds a contact to a group (VB-6B.1 semantics): find-or-create the
     * tenant-level identity, then upsert the membership through the
     * canonical membership service. The identity is tenant-scoped — the
     * same number in another group resolves to the SAME Contact.
     * Concurrency: the tenant+phone live unique index is the final
     * authority; a raced insert surfaces at flush and becomes the typed
     * conflict (the transaction is already aborted at that point, so
     * same-transaction recovery is impossible by design — competing
     * callers receive the deterministic 409).
     */
    @Transactional
    public ApiResponse<ContactResponse> createContact(UUID groupId, CreateContactRequest request) {
        ContactGroupEntity group = groupAccess.authorizedGroup(groupId, CAP_MANAGE);
        UUID tenantId = group.getTenantId();

        String canonicalPhone = contactIdentityService.canonicalPhoneNumber(request.phoneNumber());

        ContactEntity identity = contactRepository
                .findByTenantIdAndPhoneNumberAndDeletedAtIsNull(tenantId, canonicalPhone)
                .orElse(null);
        if (identity == null) {
            try {
                identity = contactRepository.save(
                        contactMapper.toEntity(request, tenantId, canonicalPhone));
                // Flush inside the guarded block: Hibernate defers the INSERT,
                // so the unique-index race must surface HERE.
                contactRepository.flush();
            } catch (DataIntegrityViolationException race) {
                throw contactIdentityService.duplicateContactConflict(tenantId, canonicalPhone);
            }
        }

        memberService.insertMembership(tenantId, groupId, identity.getId());
        return ResponseFactory.created(contactMapper.toResponse(identity));
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<ContactResponse>> listContacts(
        UUID groupId, int page, int size, String[] sortArr, String search
    ) {
        ContactGroupEntity group = groupAccess.authorizedGroup(groupId, CAP_VIEW);

        // VB-6B.1: the group's audience is its live memberships; contact
        // payload is joined through the membership rows.
        List<UUID> memberContactIds = memberService.memberContactIds(groupId);
        if (memberContactIds.isEmpty()) {
            return ResponseFactory.page(List.of(), PaginationMetadata.of(page, size, 0));
        }

        Specification<ContactEntity> specification = ContactSpecifications.compose(
            ContactSpecifications.liveContactInIds(memberContactIds),
            ContactSpecifications.search(search));

        Page<ContactEntity> resultPage =
            contactRepository.findAll(specification, buildPageable(page, size, sortArr, DEFAULT_CONTACT_SORT, CONTACT_SORTABLE_FIELDS));
        List<ContactResponse> items = resultPage.getContent().stream()
            .map(contactMapper::toResponse).toList();
        return ResponseFactory.page(items, PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public ApiResponse<ContactResponse> getContact(UUID groupId, UUID contactId) {
        ContactGroupEntity group = groupAccess.authorizedGroup(groupId, CAP_VIEW);
        // VB-6B.1: identity is tenant-scoped; the group path authorizes the
        // tenant, the contact itself must live in that tenant.
        ContactEntity entity = contactRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(contactId, group.getTenantId())
                .orElseThrow(ContactGroupAccess::contactNotFound);
        return ResponseFactory.ok(contactMapper.toResponse(entity));
    }

    @Transactional
    public ApiResponse<ContactResponse> updateContact(
        UUID groupId, UUID contactId, UpdateContactRequest request
    ) {
        ContactGroupEntity group = groupAccess.authorizedGroup(groupId, CAP_MANAGE);
        // VB-6B.1: identity update is tenant-scoped — the group path
        // authorizes the tenant; the contact must live in that tenant.
        ContactEntity entity = contactRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(contactId, group.getTenantId())
                .orElseThrow(ContactGroupAccess::contactNotFound);

        // Canonicalization + identity dedup: a phone change keeps the same
        // Contact UUID (history stays attached); a collision with another
        // live tenant identity is a typed conflict.
        String canonicalPhone = contactIdentityService.canonicalPhoneNumber(request.phoneNumber());
        if (!canonicalPhone.equals(entity.getPhoneNumber())) {
            contactIdentityService.assertPhoneAvailableForTenant(entity.getTenantId(), canonicalPhone);
        }

        try {
            contactMapper.updateEntity(entity, request, canonicalPhone);
            contactRepository.save(entity);
            // See createContact: the constraint race must surface here.
            contactRepository.flush();
        } catch (DataIntegrityViolationException race) {
            throw contactIdentityService.duplicateContactConflict(entity.getTenantId(), canonicalPhone);
        }
        return ResponseFactory.ok(contactMapper.toResponse(entity));
    }

    /**
     * Soft-deletes a contact identity (VB-6B.1): the row and its call
     * history remain; the identity stops being live (its phone becomes
     * re-creatable) and its memberships are physically removed through
     * the canonical membership service — a deleted contact is in no live
     * audience.
     */
    @Transactional
    public void deleteContact(UUID groupId, UUID contactId) {
        ContactGroupEntity group = groupAccess.authorizedGroup(groupId, CAP_MANAGE);
        ContactEntity entity = contactRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(contactId, group.getTenantId())
                .orElseThrow(ContactGroupAccess::contactNotFound);

        memberService.removeAllForContact(contactId);

        Instant now = Instant.now();
        entity.setDeletedAt(now);
        entity.setDeletedBy(groupAccess.requireUserId().toString());
        contactRepository.save(entity);
    }

    // === bulk import / export ===

    /** File download payload for the export endpoints (never ApiResponse-wrapped). */
    public record ExportFile(byte[] content, String contentType, String filename) {
    }

    /**
     * Synchronous bulk import into an existing group (VB-6B.1 semantics).
     * Ownership comes exclusively from the authorized group — never from
     * file contents. Rows are validated individually; phone values are
     * canonicalized so equivalent formatted variants collapse onto one
     * identity. Every valid row yields a membership (via the canonical
     * membership service); the first row of a number creates the
     * tenant-level Contact identity, later rows (same file or
     * already-live numbers) reuse it and are reported as duplicates.
     * Valid rows persist in one transaction.
     */
    @Transactional
    public ApiResponse<ContactImportResponse> importContacts(UUID groupId, MultipartFile file) {
        ContactGroupEntity group = groupAccess.authorizedGroup(groupId, CAP_MANAGE);
        UUID tenantId = group.getTenantId();

        if (file == null || file.isEmpty()) {
            throw business("Import file is empty.");
        }
        ContactImportReader reader = selectReader(file.getOriginalFilename());
        List<ContactRowData> rows = reader.read(readStreamSafely(file));
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw business("Import exceeds the maximum supported number of rows (" + MAX_IMPORT_ROWS + ").");
        }

        int created = 0;
        int duplicateCount = 0;
        int errorCount = 0;
        Set<String> seenPhones = new HashSet<>();
        Map<String, ContactEntity> newIdentities = new LinkedHashMap<>();
        Map<String, UUID> existingIdentityIds = new LinkedHashMap<>();
        List<ContactImportError> errors = new ArrayList<>();
        // Batched identity lookup across the whole tenant (avoids one
        // existence query per row); soft-deleted numbers are absent by
        // definition and therefore reusable.
        Set<String> existingPhones = new HashSet<>(
            contactRepository.findLivePhoneNumbers(tenantId));

        for (ContactRowData row : rows) {
            // Deterministic canonical identity: separator-stripped, then
            // validated against the E.164 contract. The canonical form is
            // both the dedup key and the stored value.
            String phoneNumber = ContactValidation.canonicalizePhoneNumber(row.phoneNumber());
            if (phoneNumber == null) {
                errorCount++;
                addError(errors, row.rowNumber(), "phoneNumber", "INVALID_E164",
                    "Phone number must be a valid E.164 number.");
                continue;
            }
            String email = trimToNull(row.email());
            if (email != null && !ContactValidation.EMAIL.matcher(email).matches()) {
                errorCount++;
                addError(errors, row.rowNumber(), "email", "INVALID_EMAIL",
                    "Email address is invalid.");
                continue;
            }
            ParsedAttributes parsedAttributes = parseAttributes(row);
            if (!parsedAttributes.valid()) {
                errorCount++;
                addError(errors, row.rowNumber(), "attributes", "MALFORMED_ATTRIBUTES",
                    "Attributes must be a valid JSON object.");
                continue;
            }

            if (!seenPhones.add(phoneNumber)) {
                // Same number twice in one file: one identity, one
                // membership — the later row is a duplicate of the first.
                duplicateCount++;
                addError(errors, row.rowNumber(), "phoneNumber", "DUPLICATE_PHONE",
                    "Duplicate phone number within the imported file.");
                continue;
            }
            if (existingPhones.contains(phoneNumber)) {
                // Already a live tenant identity: reuse it, add the
                // membership, report the row as a duplicate. The id is
                // resolved once per distinct number (batched with the
                // membership upsert below).
                duplicateCount++;
                addError(errors, row.rowNumber(), "phoneNumber", "DUPLICATE_PHONE",
                    "A contact with this phone number already exists.");
                existingIdentityIds.computeIfAbsent(phoneNumber,
                    phone -> contactRepository
                        .findByTenantIdAndPhoneNumberAndDeletedAtIsNull(tenantId, phone)
                        .map(ContactEntity::getId)
                        .orElse(null));
                continue;
            }

            CreateContactRequest request = new CreateContactRequest(
                trimToNull(row.firstName()), trimToNull(row.lastName()),
                phoneNumber, email, parsedAttributes.value());
            newIdentities.put(phoneNumber,
                    contactMapper.toEntity(request, tenantId, phoneNumber));
        }

        if (!newIdentities.isEmpty()) {
            try {
                contactRepository.saveAll(new ArrayList<>(newIdentities.values()));
                // Flush inside the guarded block so unique-index races on
                // the identity surface here.
                contactRepository.flush();
            } catch (DataIntegrityViolationException race) {
                // Deviation from find-or-create purity: an import is one
                // transaction — a raced identity creation fails the whole
                // import deterministically instead of half-completing.
                throw business("A contact with a phone number from this import was created concurrently. Retry the import.");
            }
            created = newIdentities.size();
            for (ContactEntity identity : new ArrayList<>(newIdentities.values())) {
                memberService.insertMembership(tenantId, groupId, identity.getId());
            }
        }
        // Memberships for identities that already existed (duplicate rows).
        for (Map.Entry<String, UUID> existing : existingIdentityIds.entrySet()) {
            if (existing.getValue() != null) {
                memberService.insertMembership(tenantId, groupId, existing.getValue());
            }
        }

        return ResponseFactory.ok(new ContactImportResponse(
            rows.size(), created, duplicateCount + errorCount, duplicateCount, errorCount,
            errors.size() > MAX_REPORTED_ERRORS ? errors.subList(0, MAX_REPORTED_ERRORS) : errors));
    }

    @Transactional(readOnly = true)
    public ExportFile exportContacts(UUID groupId, String format) {
        groupAccess.authorizedGroup(groupId, CAP_VIEW);

        String normalizedFormat = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        // VB-6B.1: export reads the audience through live memberships.
        List<UUID> memberContactIds = memberService.memberContactIds(groupId);
        List<ContactEntity> contacts = memberContactIds.isEmpty()
            ? List.of()
            : contactRepository.findAll(
                ContactSpecifications.liveContactInIds(memberContactIds),
                Sort.by(Sort.Direction.ASC, "createdAt"));

        return switch (normalizedFormat) {
            case "csv" -> new ExportFile(toCsv(contacts),
                "text/csv", "contacts-" + groupId + ".csv");
            case "xlsx" -> new ExportFile(toXlsx(contacts),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "contacts-" + groupId + ".xlsx");
            case "json" -> new ExportFile(toJson(contacts),
                "application/json", "contacts-" + groupId + ".json");
            default -> throw business("Unsupported export format. Supported: csv, xlsx, json.");
        };
    }

    // === internal ===

    private ContactImportReader selectReader(String filename) {
        return importReaders.stream()
            .filter(reader -> reader.supports(filename))
            .findFirst()
            .orElseThrow(() -> business("Unsupported file format. Supported: .csv, .xlsx, .json"));
    }

    private static InputStream readStreamSafely(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (IOException ex) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Uploaded file is unreadable.");
        }
    }
    /** Attributes outcome for one import row. */
    private record ParsedAttributes(boolean valid, JsonNode value) {

        static ParsedAttributes absent() {
            return new ParsedAttributes(true, null);
        }

        static ParsedAttributes invalid() {
            return new ParsedAttributes(false, null);
        }
    }

    /**
     * Row-level attributes validation: a missing/blank value is valid
     * (absent); anything present must parse as a JSON object. Jackson 3
     * parse errors are unchecked by design — they are converted into the
     * row outcome, never swallowed silently.
     */
    private ParsedAttributes parseAttributes(ContactRowData row) {
        String raw = row.attributesJson();
        if (raw == null || raw.isBlank()) {
            return ParsedAttributes.absent();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node.isObject()
                ? new ParsedAttributes(true, node)
                : ParsedAttributes.invalid();
        } catch (JacksonException ex) {
            return ParsedAttributes.invalid();
        }
    }

    private static void addError(
        List<ContactImportError> errors, int rowNumber, String field, String code, String message
    ) {
        if (errors.size() < MAX_REPORTED_ERRORS) {
            errors.add(new ContactImportError(rowNumber, field, code, message));
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Pageable buildPageable(int page, int size, String[] sortArr, Sort defaultSort, List<String> allowedFields) {
        Sort sort = defaultSort;
        if (sortArr != null && sortArr.length > 0 && allowedFields.contains(sortArr[0])) {
            Sort.Direction direction = sortArr.length > 1 && "asc".equalsIgnoreCase(sortArr[1])
                ? Sort.Direction.ASC : Sort.Direction.DESC;
            sort = Sort.by(direction, sortArr[0]);
        }
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
    }

    // === member count support (VB-6B.2) ===

    /** Attaches the live member count to one group response. */
    private static ContactGroupResponse withMemberCount(ContactGroupResponse response, Long count) {
        return new ContactGroupResponse(response.id(), response.tenantId(), response.name(),
            response.description(), count, response.createdAt(), response.updatedAt());
    }

    /** Attaches live member counts to a page of groups with ONE grouped query. */
    private List<ContactGroupResponse> withMemberCounts(List<ContactGroupResponse> responses) {
        if (responses.isEmpty()) {
            return responses;
        }
        List<UUID> groupIds = responses.stream().map(ContactGroupResponse::id).toList();
        Map<UUID, Long> counts = memberService.memberCounts(groupIds);
        List<ContactGroupResponse> result = new ArrayList<>(responses.size());
        for (ContactGroupResponse response : responses) {
            result.add(withMemberCount(response, counts.getOrDefault(response.id(), 0L)));
        }
        return result;
    }

    // === export serializers ===

    private byte[] toCsv(List<ContactEntity> contacts) {
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            printer.printRecord(EXPORT_COLUMNS);
            for (ContactEntity contact : contacts) {
                printer.printRecord(
                    contact.getPhoneNumber(),
                    contact.getFirstName(),
                    contact.getLastName(),
                    contact.getEmail(),
                    contact.getAttributes() == null ? null : objectMapper.writeValueAsString(contact.getAttributes()));
            }
        } catch (IOException ex) { // StringWriter never throws IOException; defensive only
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, "CSV export failed.");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] toXlsx(List<ContactEntity> contacts) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("contacts");
            Row header = sheet.createRow(0);
            for (int c = 0; c < EXPORT_COLUMNS.size(); c++) {
                header.createCell(c).setCellValue(EXPORT_COLUMNS.get(c));
            }
            int r = 1;
            for (ContactEntity contact : contacts) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(contact.getPhoneNumber());
                row.createCell(1).setCellValue(orEmpty(contact.getFirstName()));
                row.createCell(2).setCellValue(orEmpty(contact.getLastName()));
                row.createCell(3).setCellValue(orEmpty(contact.getEmail()));
                row.createCell(4).setCellValue(contact.getAttributes() == null
                    ? "" : objectMapper.writeValueAsString(contact.getAttributes()));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) { // in-memory streams never throw; defensive only
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, "XLSX export failed.");
        }
    }

    private byte[] toJson(List<ContactEntity> contacts) {
        List<Map<String, Object>> payload = new ArrayList<>(contacts.size());
        for (ContactEntity contact : contacts) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("phoneNumber", contact.getPhoneNumber());
            entry.put("firstName", contact.getFirstName());
            entry.put("lastName", contact.getLastName());
            entry.put("email", contact.getEmail());
            entry.put("attributes", contact.getAttributes());
            payload.add(entry);
        }
        return objectMapper.writeValueAsBytes(payload);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private BusinessException business(String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_ERROR, message);
    }

    static ResourceNotFoundException contactNotFound() {
        return ContactGroupAccess.contactNotFound();
    }
}
