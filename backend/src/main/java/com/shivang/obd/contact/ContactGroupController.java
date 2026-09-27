package com.shivang.obd.contact;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.contact.dto.AddMemberRequest;
import com.shivang.obd.contact.dto.BatchMemberRequest;
import com.shivang.obd.contact.dto.BatchMemberResponse;
import com.shivang.obd.contact.dto.ContactGroupMemberResponse;
import com.shivang.obd.contact.dto.ContactGroupResponse;
import com.shivang.obd.contact.dto.ContactImportResponse;
import com.shivang.obd.contact.dto.ContactResponse;
import com.shivang.obd.contact.dto.CreateContactGroupRequest;
import com.shivang.obd.contact.dto.CreateContactRequest;
import com.shivang.obd.contact.dto.UpdateContactGroupRequest;
import com.shivang.obd.contact.dto.UpdateContactRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/contact-groups")
@RequiredArgsConstructor
@Tag(name = "Contact Groups", description = "Tenant-owned contact groups and their contacts. "
    + "Listing and reads are implicitly scoped to the caller's organizational boundary "
    + "(own tenant / active tenants of the reseller hierarchy / platform).")
public class ContactGroupController {

    private final ContactGroupService contactGroupService;
    private final ContactGroupMemberService memberService;

    // === groups ===

    @Operation(
        summary = "Create a contact group",
        description = "Creates a group owned by the caller's context tenant. Referenced later "
            + "by campaigns through the group identifier.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Group created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability")
    @PostMapping
    public ResponseEntity<ApiResponse<ContactGroupResponse>> create(
        @Valid @RequestBody CreateContactGroupRequest request
    ) {
        ApiResponse<ContactGroupResponse> body = contactGroupService.createGroup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "Get a contact group",
        description = "Scoped lookups constrain access to the caller's boundary, so a foreign "
            + "group and a nonexistent group are indistinguishable (404).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @GetMapping("/{id}")
    public ApiResponse<ContactGroupResponse> getById(@PathVariable UUID id) {
        return contactGroupService.getGroup(id);
    }

    @Operation(
        summary = "List contact groups",
        description = "Paginated, searchable listing scoped to the caller's organizational "
            + "context. Soft-deleted groups are always excluded.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter or sort")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_VIEW capability")
    @GetMapping
    public ApiResponse<List<ContactGroupResponse>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt,desc") String[] sort,
        @RequestParam(required = false) String search
    ) {
        return contactGroupService.listGroups(page, size, sort, search);
    }

    @Operation(
        summary = "Update a contact group",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @PutMapping("/{id}")
    public ApiResponse<ContactGroupResponse> update(
        @PathVariable UUID id, @Valid @RequestBody UpdateContactGroupRequest request
    ) {
        return contactGroupService.updateGroup(id, request);
    }

    @Operation(
        summary = "Delete a contact group",
        description = "Soft delete. Rejected with 409 while the group still contains contacts.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Group still contains contacts")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        contactGroupService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }

    // === contacts ===

    @Operation(
        summary = "Add a contact to a group",
        description = "Creates a contact inside an existing group; ownership is inherited from "
            + "the group.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Contact created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group not found or outside caller boundary")
    @PostMapping("/{id}/contacts")
    public ResponseEntity<ApiResponse<ContactResponse>> addContact(
        @PathVariable UUID id, @Valid @RequestBody CreateContactRequest request
    ) {
        ApiResponse<ContactResponse> body = contactGroupService.createContact(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "List contacts in a group",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group not found or outside caller boundary")
    @GetMapping("/{id}/contacts")
    public ApiResponse<List<ContactResponse>> listContacts(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "firstName,asc") String[] sort,
        @RequestParam(required = false) String search
    ) {
        return contactGroupService.listContacts(id, page, size, sort, search);
    }

    @Operation(
        summary = "Get a contact in a group",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group or contact not found / outside caller boundary")
    @GetMapping("/{id}/contacts/{contactId}")
    public ApiResponse<ContactResponse> getContact(
        @PathVariable UUID id, @PathVariable UUID contactId
    ) {
        return contactGroupService.getContact(id, contactId);
    }

    @Operation(
        summary = "Update a contact in a group",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group or contact not found / outside caller boundary")
    @PutMapping("/{id}/contacts/{contactId}")
    public ApiResponse<ContactResponse> updateContact(
        @PathVariable UUID id,
        @PathVariable UUID contactId,
        @Valid @RequestBody UpdateContactRequest request
    ) {
        return contactGroupService.updateContact(id, contactId, request);
    }

    @Operation(
        summary = "Delete a contact from a group",
        description = "Soft delete: the row is preserved with deletion audit columns stamped.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group or contact not found / outside caller boundary")
    @DeleteMapping("/{id}/contacts/{contactId}")
    public ResponseEntity<Void> deleteContact(
        @PathVariable UUID id, @PathVariable UUID contactId
    ) {
        contactGroupService.deleteContact(id, contactId);
        return ResponseEntity.noContent().build();
    }

    // === members (VB-6B.2) ===

    @Operation(
        summary = "List group members",
        description = "Paginated member roster of a contact group. Membership-led: each entry "
            + "is a physical (group, contact) relationship with the live contact payload "
            + "embedded. Soft-deleted contacts never appear. Search matches case-insensitively "
            + "against firstName, lastName and phoneNumber.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter or sort")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group not found or outside caller boundary")
    @GetMapping("/{id}/members")
    public ApiResponse<List<ContactGroupMemberResponse>> listMembers(
        @Parameter(description = "Contact group id", required = true) @PathVariable UUID id,
        @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
        @Parameter(description = "Page size (1-100)", example = "20") @RequestParam(defaultValue = "20") int size,
        @Parameter(description = "Sort as \"field,direction\"; allowed fields: createdAt, firstName, phoneNumber. Invalid fields fall back to createdAt,asc.", example = "createdAt,asc")
        @RequestParam(defaultValue = "createdAt,asc") String[] sort,
        @Parameter(description = "Case-insensitive contains search over firstName, lastName, phoneNumber", example = "rahul")
        @RequestParam(required = false) String search
    ) {
        return memberService.listMembers(id, page, size, sort, search);
    }

    @Operation(
        summary = "Add a member to a group",
        description = "Adds an existing contact as a group member. The contact must be a live "
            + "identity of the group's tenant — the API never creates contacts; a foreign-tenant "
            + "contact is indistinguishable from a nonexistent one (404). Idempotent: adding an "
            + "existing member returns 200 with the current membership; a concurrent duplicate is "
            + "resolved by the database unique constraint as the same idempotent outcome.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Membership created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Contact was already a member (idempotent)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group, or contact within the group's tenant, not found / outside caller boundary")
    @PostMapping("/{id}/members")
    public ResponseEntity<ApiResponse<ContactGroupMemberResponse>> addMember(
        @Parameter(description = "Contact group id", required = true) @PathVariable UUID id,
        @Valid @RequestBody AddMemberRequest request
    ) {
        ContactGroupMemberService.AddOutcome outcome = memberService.addMember(id, request);
        ApiResponse<ContactGroupMemberResponse> body = outcome.created()
            ? ResponseFactory.created(outcome.member())
            : ResponseFactory.ok(outcome.member());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(body);
    }

    @Operation(
        summary = "Get a group member",
        description = "Returns the membership of one contact in the group. Nonexistent group, "
            + "unauthorized group, foreign tenant and nonexistent membership are "
            + "indistinguishable (404).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group or membership not found / outside caller boundary")
    @GetMapping("/{id}/members/{contactId}")
    public ApiResponse<ContactGroupMemberResponse> getMember(
        @Parameter(description = "Contact group id", required = true) @PathVariable UUID id,
        @Parameter(description = "Contact id of the member", required = true) @PathVariable UUID contactId
    ) {
        return memberService.getMember(id, contactId);
    }

    @Operation(
        summary = "Remove a member from a group",
        description = "Removes the membership (physical relationship row). Idempotent: removing "
            + "a missing membership is a successful no-op. Contact existence is irrelevant to "
            + "removal. Always 204 when the group is authorized.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Membership removed (or absent — idempotent no-op)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group not found or outside caller boundary")
    @DeleteMapping("/{id}/members/{contactId}")
    public ResponseEntity<Void> removeMember(
        @Parameter(description = "Contact group id", required = true) @PathVariable UUID id,
        @Parameter(description = "Contact id of the member", required = true) @PathVariable UUID contactId
    ) {
        memberService.removeMember(id, contactId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Batch add members",
        description = "Adds several contacts as members. Never fail-fast: every distinct "
            + "contact id receives an independent per-item outcome (CREATED / EXISTS / "
            + "NOT_FOUND_CONTACT / ERROR). Duplicate ids within the request collapse to a single "
            + "outcome and never create duplicate rows.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch processed; per-item outcomes reported")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed (empty list, over 500 items)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group not found or outside caller boundary")
    @PostMapping("/{id}/members/batch")
    public ApiResponse<BatchMemberResponse> addMembersBatch(
        @Parameter(description = "Contact group id", required = true) @PathVariable UUID id,
        @Valid @RequestBody BatchMemberRequest request
    ) {
        return ResponseFactory.ok(memberService.addMembers(id, request));
    }

    @Operation(
        summary = "Batch remove members",
        description = "Removes several memberships. Never fail-fast: every distinct contact id "
            + "receives an independent per-item outcome (CREATED when a row was removed, "
            + "NOT_FOUND when there was nothing to remove). Contact existence is irrelevant.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch processed; per-item outcomes reported")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed (empty list, over 500 items)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group not found or outside caller boundary")
    @DeleteMapping("/{id}/members/batch")
    public ApiResponse<BatchMemberResponse> removeMembersBatch(
        @Parameter(description = "Contact group id", required = true) @PathVariable UUID id,
        @Valid @RequestBody BatchMemberRequest request
    ) {
        return ResponseFactory.ok(memberService.removeMembers(id, request));
    }

    // === bulk import / export ===

    @Operation(
        summary = "Bulk-import contacts into a group",
        description = "Uploads a .csv, .xlsx or .json file (multipart/form-data, field name "
            + "'file', max 5 MB / 5000 rows). Required column: phoneNumber (E.164). Optional "
            + "columns: firstName, lastName, email, attributes (a JSON object string). Rows are "
            + "validated individually; duplicates within the file and against existing live "
            + "contacts are skipped and reported. Ownership comes exclusively from the group — "
            + "ownership data in the file is ignored.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Import processed; per-row errors reported in the result")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Empty/unsupported/malformed file or missing required column")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group not found or outside caller boundary")
    @PostMapping(value = "/{id}/contacts/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ContactImportResponse> importContacts(
        @PathVariable UUID id,
        @RequestParam("file") MultipartFile file
    ) {
        return contactGroupService.importContacts(id, file);
    }

    @Operation(
        summary = "Export a group's contacts",
        description = "Downloads the group's live contacts as csv, xlsx or json. Exports only "
            + "the contact payload (phoneNumber, firstName, lastName, email, attributes); "
            + "ownership and audit fields are never included. Response is a raw file download, "
            + "not the JSON envelope.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "File download")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Unsupported format")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CONTACT_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group not found or outside caller boundary")
    @GetMapping("/{id}/contacts/export")
    public ResponseEntity<byte[]> exportContacts(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "csv") String format
    ) {
        ContactGroupService.ExportFile export = contactGroupService.exportContacts(id, format);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(export.contentType()))
            .header("Content-Disposition", "attachment; filename=\"" + export.filename() + "\"")
            .body(export.content());
    }
}
