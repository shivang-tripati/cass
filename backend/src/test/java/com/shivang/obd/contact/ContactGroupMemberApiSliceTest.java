package com.shivang.obd.contact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.response.PaginationMetadata;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.contact.dto.BatchMemberResponse;
import com.shivang.obd.contact.dto.BatchMemberResult;
import com.shivang.obd.contact.dto.ContactGroupMemberResponse;
import com.shivang.obd.contact.dto.MemberBatchStatus;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * VB-6B.2 API slice test — the first standalone-MockMvc suite for
 * {@link ContactGroupController} (pattern: QueueDirectoryApiSliceTest).
 * Verifies the HTTP contract of the member endpoints: status codes
 * (201/200/204), envelope shape, validation → 400 ProblemDetail,
 * 404-cloaking, batch response shape and the pagination metadata.
 * Full security wiring (401/403) is covered centrally by
 * SecuritySliceTest; service-level capability checks by the unit tests.
 */
class ContactGroupMemberApiSliceTest {

    private static final UUID USER_ID = UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID GROUP_A = UUID.fromString("55000000-0000-4000-8000-000000000005");
    private static final UUID CONTACT_1 = UUID.fromString("3f2b8c40-9e11-4f7a-b6c2-8d3e5a901234");

    private MockMvc mockMvc;
    private ContactGroupMemberRepository memberRepository;
    private ContactRepository contactRepository;
    private ContactGroupRepository groupRepository;

    @BeforeEach
    void setUp() {
        memberRepository = mock(ContactGroupMemberRepository.class);
        contactRepository = mock(ContactRepository.class);
        groupRepository = mock(ContactGroupRepository.class);
        TenantRepository tenantRepository = mock(TenantRepository.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.current()).thenReturn(Optional.of(
                new AuthenticatedUser(USER_ID, "member@example.com", null)));

        ContactGroupEntity group = new ContactGroupEntity();
        group.setId(GROUP_A);
        group.setTenantId(TENANT_A);
        group.setName("Slice group");
        when(groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(GROUP_A, TENANT_A))
            .thenReturn(Optional.of(group));

        ContactGroupAccess groupAccess = new ContactGroupAccess(
            groupRepository, authorizationService, currentUserProvider, tenantRepository);
        ContactGroupMemberService memberService = new ContactGroupMemberService(
            memberRepository, contactRepository, groupAccess, currentUserProvider);
        ContactGroupMemberService memberServiceSpy = memberService;
        ContactGroupService groupService = new ContactGroupService(
            groupRepository, contactRepository, memberServiceSpy,
            new ContactIdentityService(contactRepository), groupAccess,
            new ContactGroupMapper(), new ContactMapper(), List.of(),
            new tools.jackson.databind.ObjectMapper());
        var controller = new ContactGroupController(groupService, memberService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.shivang.obd.common.exception.GlobalExceptionHandler())
                .build();

        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    private ContactGroupMemberEntity memberRow() {
        ContactEntity contact = new ContactEntity();
        contact.setId(CONTACT_1);
        contact.setTenantId(TENANT_A);
        contact.setPhoneNumber("+919876543210");
        contact.setFirstName("Rahul");
        ContactGroupMemberEntity row = new ContactGroupMemberEntity();
        row.setId(UUID.fromString("7e6a1c50-4d21-4e9f-9d3a-2b8f5a111001"));
        row.setTenantId(TENANT_A);
        row.setContactGroupId(GROUP_A);
        row.setContactId(CONTACT_1);
        row.setCreatedBy(USER_ID.toString());
        row.setCreatedAt(Instant.parse("2026-09-26T10:15:30Z"));
        row.setContact(contact);
        return row;
    }

    private void stubSuccessfulAdd() {
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(CONTACT_1, TENANT_A))
            .thenReturn(Optional.of(memberRow().getContact()));
        when(memberRepository.existsByContactGroupIdAndContactId(GROUP_A, CONTACT_1)).thenReturn(false);
        when(memberRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("API-M1: POST member creates → 201 with ApiResponse envelope")
    void addMemberReturns201WithEnvelope() throws Exception {
        stubSuccessfulAdd();

        mockMvc.perform(post("/api/v1/contact-groups/{id}/members", GROUP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":\"" + CONTACT_1 + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.contactId").value(CONTACT_1.toString()))
                .andExpect(jsonPath("$.data.groupId").value(GROUP_A.toString()))
                .andExpect(jsonPath("$.data.tenantId").value(TENANT_A.toString()))
                .andExpect(jsonPath("$.data.contact.phoneNumber").value("+919876543210"));
    }

    @Test
    @DisplayName("API-M2: POST of an existing member → 200 (idempotent EXISTS)")
    void addExistingMemberReturns200() throws Exception {
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(CONTACT_1, TENANT_A))
            .thenReturn(Optional.of(memberRow().getContact()));
        when(memberRepository.existsByContactGroupIdAndContactId(GROUP_A, CONTACT_1)).thenReturn(true);
        when(memberRepository.findByContactGroupIdAndContactId(GROUP_A, CONTACT_1))
            .thenReturn(Optional.of(memberRow()));

        mockMvc.perform(post("/api/v1/contact-groups/{id}/members", GROUP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":\"" + CONTACT_1 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.contactId").value(CONTACT_1.toString()));
    }

    @Test
    @DisplayName("API-M3: invalid request body → 400 ProblemDetail")
    void invalidBodyIs400() throws Exception {
        mockMvc.perform(post("/api/v1/contact-groups/{id}/members", GROUP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("API-M4: unknown contact → 404 problem detail (404-cloak)")
    void unknownContactIs404() throws Exception {
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(CONTACT_1, TENANT_A))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/contact-groups/{id}/members", GROUP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":\"" + CONTACT_1 + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("API-M5: GET roster → 200 envelope with pagination metadata")
    void rosterReturns200WithPagination() throws Exception {
        when(memberRepository.findRosterPage(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(memberRow()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/contact-groups/{id}/members", GROUP_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].contactId").value(CONTACT_1.toString()))
                .andExpect(jsonPath("$.pagination.page").value(0))
                .andExpect(jsonPath("$.pagination.size").value(20))
                .andExpect(jsonPath("$.pagination.totalElements").value(1));
    }

    @Test
    @DisplayName("API-M6: GET member → 200; unknown member → 404")
    void getMemberAndMissingMember() throws Exception {
        when(memberRepository.findByContactGroupIdAndContactId(GROUP_A, CONTACT_1))
            .thenReturn(Optional.of(memberRow()));

        mockMvc.perform(get("/api/v1/contact-groups/{id}/members/{contactId}", GROUP_A, CONTACT_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").isNotEmpty())
                .andExpect(jsonPath("$.data.createdBy").value(USER_ID.toString()));

        UUID stranger = UUID.randomUUID();
        when(memberRepository.findByContactGroupIdAndContactId(GROUP_A, stranger))
            .thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/contact-groups/{id}/members/{contactId}", GROUP_A, stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("API-M7: DELETE member → 204 (idempotent, also for missing membership)")
    void deleteMemberReturns204() throws Exception {
        when(memberRepository.deleteByContactGroupIdAndContactId(GROUP_A, CONTACT_1)).thenReturn(1L);

        mockMvc.perform(delete("/api/v1/contact-groups/{id}/members/{contactId}", GROUP_A, CONTACT_1))
                .andExpect(status().isNoContent());

        UUID absent = UUID.randomUUID();
        when(memberRepository.deleteByContactGroupIdAndContactId(GROUP_A, absent)).thenReturn(0L);
        mockMvc.perform(delete("/api/v1/contact-groups/{id}/members/{contactId}", GROUP_A, absent))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("API-M8: batch add → 200 with per-item outcome shape")
    void batchAddReturnsDocumentedShape() throws Exception {
        UUID created = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(created, TENANT_A))
            .thenReturn(Optional.of(memberRow().getContact()));
        when(memberRepository.existsByContactGroupIdAndContactId(GROUP_A, created)).thenReturn(false);
        when(memberRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/contact-groups/{id}/members/batch", GROUP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactIds\":[\"" + created + "\",\"" + missing + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.results.length()").value(2))
                .andExpect(jsonPath("$.data.results[0].status").value("CREATED"))
                .andExpect(jsonPath("$.data.results[1].status").value("NOT_FOUND_CONTACT"))
                .andExpect(jsonPath("$.data.processed").value(2))
                .andExpect(jsonPath("$.data.failed").value(0));
    }

    @Test
    @DisplayName("API-M9: batch add with an empty list → 400; unknown group → 404")
    void batchValidationAndCloaking() throws Exception {
        mockMvc.perform(post("/api/v1/contact-groups/{id}/members/batch", GROUP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactIds\":[]}"))
                .andExpect(status().isBadRequest());

        UUID foreign = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/contact-groups/{id}/members/batch", foreign)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactIds\":[\"" + CONTACT_1 + "\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("API-M10: batch remove → 200 with per-item outcomes (CREATED/NOT_FOUND)")
    void batchRemoveShape() throws Exception {
        UUID removed = UUID.randomUUID();
        when(memberRepository.deleteByContactGroupIdAndContactId(GROUP_A, removed)).thenReturn(1L);
        when(memberRepository.deleteByContactGroupIdAndContactId(GROUP_A, CONTACT_1)).thenReturn(0L);

        mockMvc.perform(delete("/api/v1/contact-groups/{id}/members/batch", GROUP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactIds\":[\"" + removed + "\",\"" + CONTACT_1 + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].status").value("CREATED"))
                .andExpect(jsonPath("$.data.results[1].status").value("NOT_FOUND"));
    }
}
