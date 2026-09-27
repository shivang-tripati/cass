package com.shivang.obd.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.contact.dto.ContactImportResponse;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * Focused coverage for genuinely new bulk-import behavior: format
 * dispatch, required-column enforcement, row-level E.164/duplicate
 * handling, and the ownership boundary on the import path.
 */
class ContactImportServiceTest {

    private static final UUID USER_ID = UUID.fromString("0f000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID GROUP_A = UUID.fromString("55000000-0000-4000-8000-000000000005");

    private ContactGroupRepository groupRepository;
    private ContactRepository contactRepository;
    private com.shivang.obd.contact.ContactGroupMemberRepository memberRepository;
    private ContactGroupService service;

    @BeforeEach
    void setUp() {
        groupRepository = org.mockito.Mockito.mock(ContactGroupRepository.class);
        contactRepository = org.mockito.Mockito.mock(ContactRepository.class);
        memberRepository = org.mockito.Mockito.mock(com.shivang.obd.contact.ContactGroupMemberRepository.class);
        AuthorizationService authorizationService = org.mockito.Mockito.mock(AuthorizationService.class);
        TenantRepository tenantRepository = org.mockito.Mockito.mock(TenantRepository.class);
        CurrentUserProvider currentUserProvider = org.mockito.Mockito.mock(CurrentUserProvider.class);
        when(currentUserProvider.current())
            .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "import-admin@test.local", null)));
        when(groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(GROUP_A, TENANT_A))
            .thenReturn(Optional.of(group()));
        com.shivang.obd.contact.ContactGroupAccess groupAccess =
            new com.shivang.obd.contact.ContactGroupAccess(
                groupRepository, authorizationService, currentUserProvider, tenantRepository);
        com.shivang.obd.contact.ContactGroupMemberService memberService =
            new com.shivang.obd.contact.ContactGroupMemberService(
                memberRepository, contactRepository, groupAccess, currentUserProvider);
        service = new ContactGroupService(groupRepository, contactRepository,
            memberService, new ContactIdentityService(contactRepository), groupAccess,
            new ContactGroupMapper(), new ContactMapper(),
            List.of(new CsvContactImportReader(),
                new JsonContactImportReader(new ObjectMapper()),
                new XlsxContactImportReader()),
            new ObjectMapper());
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    private ContactGroupEntity group() {
        ContactGroupEntity entity = new ContactGroupEntity();
        entity.setId(GROUP_A);
        entity.setTenantId(TENANT_A);
        entity.setName("Import target");
        return entity;
    }

    private MockMultipartFile file(String filename, String content) {
        return new MockMultipartFile("file", filename, null,
            content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void csvRowsAreValidatedAndDuplicatesSkippedPerRow() {
        String csv = """
            phoneNumber,firstName,lastName,email,attributes
            +919876543210,Shivang,Tripathi,shivang@example.com,"{""orderId"":""ORD-1001""}"
            +919812345678,Amit,Kumar,amit@example.com,
            +919876543210,Duplicate,Dup,dup@example.com,
            12345,Bad,Phone,bad@none,
            """;
        when(contactRepository.findLivePhoneNumbers(TENANT_A)).thenReturn(List.of("+919812345678"));
        when(contactRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<ContactEntity> toSave = inv.getArgument(0);
            for (ContactEntity c : toSave) {
                c.setId(UUID.randomUUID());
            }
            return toSave;
        });

        ContactImportResponse result =
            service.importContacts(GROUP_A, file("contacts.csv", csv)).data();

        assertThat(result.totalRows()).isEqualTo(4);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.duplicateCount()).isEqualTo(2); // in-file + existing
        assertThat(result.errorCount()).isEqualTo(1);     // malformed phone + invalid email combined? no: 1 bad-phone row also has bad email -> still one row error (phone first)
        assertThat(result.errors()).anySatisfy(e -> {
            assertThat(e.code()).isEqualTo("DUPLICATE_PHONE");
        });
        assertThat(result.errors()).anySatisfy(e -> {
            assertThat(e.code()).isEqualTo("INVALID_E164");
        });
    }

    @Test
    void jsonArrayImportsAndReportsMalformedAttributesPerRow() {
        String json = """
            [
              {"phoneNumber":"+918111222333","firstName":"Neha"},
              {"phoneNumber":"+918111222334","firstName":"Ravi","attributes":"{not-json"}
            ]
            """;
        when(contactRepository.findLivePhoneNumbers(TENANT_A)).thenReturn(List.of());
        when(contactRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<ContactEntity> toSave = inv.getArgument(0);
            for (ContactEntity c : toSave) {
                c.setId(UUID.randomUUID());
            }
            return toSave;
        });

        ContactImportResponse result =
            service.importContacts(GROUP_A, file("contacts.json", json)).data();

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.errorCount()).isEqualTo(1);
        assertThat(result.errors().get(0).code()).isEqualTo("MALFORMED_ATTRIBUTES");
        assertThat(result.errors().get(0).rowNumber()).isEqualTo(2);
    }

    @Test
    void formattedPhoneVariantsCollapseOntoTheCanonicalIdentity() {
        String csv = """
            phoneNumber,firstName
            +919876543210,Canonical First,
            +91 9876543210,Variant Spaces,
            +91-9876543210,Variant Dashes,
            """;
        when(contactRepository.findLivePhoneNumbers(TENANT_A)).thenReturn(List.of());
        List<ContactEntity> savedContacts = new ArrayList<>();
        when(contactRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<ContactEntity> toSave = inv.getArgument(0);
            for (ContactEntity c : toSave) {
                c.setId(UUID.randomUUID());
                savedContacts.add(c);
            }
            return toSave;
        });

        ContactImportResponse result =
            service.importContacts(GROUP_A, file("contacts.csv", csv)).data();

        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.duplicateCount()).isEqualTo(2);
        // Persisted value is canonical; only the first occurrence wins.
        assertThat(savedContacts).hasSize(1);
        assertThat(savedContacts.get(0).getPhoneNumber()).isEqualTo("+919876543210");
        assertThat(savedContacts.get(0).getFirstName()).isEqualTo("Canonical First");
    }

    @Test
    void softDeletedPhoneNumberIsReusable() {
        String csv = """
            phoneNumber,firstName
            +919876543210,Reclaimed
            """;
        // Live phones exclude soft-deleted rows by definition.
        when(contactRepository.findLivePhoneNumbers(TENANT_A)).thenReturn(List.of());
        when(contactRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<ContactEntity> toSave = inv.getArgument(0);
            for (ContactEntity c : toSave) {
                c.setId(UUID.randomUUID());
            }
            return toSave;
        });

        ContactImportResponse result =
            service.importContacts(GROUP_A, file("contacts.csv", csv)).data();

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.duplicateCount()).isZero();
    }

    @Test
    void missingPhoneNumberColumnIsAFileLevel400() {
        String csv = """
            firstName,email
            Someone,someone@example.com
            """;
        MockMultipartFile upload = file("contacts.csv", csv);

        assertThatThrownBy(() -> service.importContacts(GROUP_A, upload))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("phoneNumber")
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(CommonErrorCode.VALIDATION_ERROR);
    }

    @Test
    void unsupportedExtensionRejectedBeforeAnyParsing() {
        MockMultipartFile upload = file("contacts.xml", "<contacts/>");

        assertThatThrownBy(() -> service.importContacts(GROUP_A, upload))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unsupported file format");
    }

    @Test
    void foreignTenantCannotImportIntoAnotherTenantsGroup() {
        UUID groupId = UUID.randomUUID();
        when(groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(eq(groupId), eq(TENANT_A)))
            .thenReturn(Optional.empty());
        MockMultipartFile upload = new MockMultipartFile("file",
            "contacts.csv", null, "phoneNumber".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.importContacts(groupId, upload))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(contactRepository, never()).saveAll(any());
    }

    private ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
