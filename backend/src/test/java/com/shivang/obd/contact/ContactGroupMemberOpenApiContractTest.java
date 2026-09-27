package com.shivang.obd.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shivang.obd.security.config.OpenApiConfig;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * VB-6B.2 OpenAPI contract verification (per the phase brief, inspecting
 * the GENERATED contract is mandatory). Runs springdoc's metadata
 * endpoint ({@code /v3/api-docs}) through MockMvc over the real
 * application context and asserts the member API contract: six
 * operations under the members paths, request/response schemas,
 * {@code memberCount} on the group response, pagination parameters, and
 * the absence of any obsolete contact-owned group field. springdoc is
 * already on the classpath (springdoc-openapi-starter-webmvc-ui); no
 * heavyweight documentation system is introduced.
 */
@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@Import({ContactGroupController.class, ContactGroupService.class,
    ContactGroupMemberService.class, ContactGroupAccess.class,
    ContactIdentityService.class, ContactGroupMapper.class, ContactMapper.class,
    OpenApiConfig.class,
    org.springdoc.core.configuration.SpringDocConfiguration.class,
    org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
    org.springdoc.core.configuration.SpringDocJavadocConfiguration.class})
class ContactGroupMemberOpenApiContractTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    private JsonNode fetchOpenApi() throws Exception {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode memberPath(JsonNode spec, String suffix) {
        return spec.at("/paths/~1api~1v1~1contact-groups~1{id}~1members" + suffix);
    }

    @Test
    @DisplayName("OAS-1: all six member operations exist under the members paths")
    void sixMemberOperationsPresent() throws Exception {
        JsonNode spec = fetchOpenApi();
        assertThat(memberPath(spec, "").has("get")).isTrue();
        assertThat(memberPath(spec, "").has("post")).isTrue();
        assertThat(memberPath(spec, "~1{contactId}").has("get")).isTrue();
        assertThat(memberPath(spec, "~1{contactId}").has("delete")).isTrue();
        assertThat(memberPath(spec, "~1batch").has("post")).isTrue();
        assertThat(memberPath(spec, "~1batch").has("delete")).isTrue();
    }

    @Test
    @DisplayName("OAS-2: the six member operationIds are present and unique")
    void operationIdsUnique() throws Exception {
        JsonNode spec = fetchOpenApi();
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.List<String> memberIds = new java.util.ArrayList<>();
        for (String pathPointer : new String[] {
                "/paths/~1api~1v1~1contact-groups~1{id}~1members",
                "/paths/~1api~1v1~1contact-groups~1{id}~1members~1{contactId}",
                "/paths/~1api~1v1~1contact-groups~1{id}~1members~1batch"}) {
            JsonNode pathNode = spec.at(pathPointer);
            for (String method : List.of("get", "post", "delete")) {
                if (pathNode.has(method)) {
                    memberIds.add(pathNode.path(method).path("operationId").asText());
                }
            }
        }
        memberIds.forEach(ids::add);
        assertThat(memberIds).hasSize(6);
        // springdoc disambiguates method-name collisions with other
        // controllers (QueueDirectoryController has list/add/removeMember)
        // by suffixing _1; the contract requirement is GLOBAL uniqueness
        // plus a stable, descriptive stem per member operation.
        assertThat(ids).hasSize(memberIds.size());
        assertThat(memberIds)
            .anySatisfy(id -> assertThat(id).startsWith("listMembers"))
            .anySatisfy(id -> assertThat(id).startsWith("addMember"))
            .anySatisfy(id -> assertThat(id).startsWith("getMember"))
            .anySatisfy(id -> assertThat(id).startsWith("removeMember"))
            .contains("addMembersBatch", "removeMembersBatch");
    }

    @Test
    @DisplayName("OAS-3: member schemas exist; AddMemberRequest.contactId is required")
    void schemasPresentAndRequired() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode schemas = spec.at("/components/schemas");
        assertThat(schemas.has("AddMemberRequest")).isTrue();
        assertThat(schemas.has("BatchMemberRequest")).isTrue();
        assertThat(schemas.has("BatchMemberResult")).isTrue();
        assertThat(schemas.has("BatchMemberResponse")).isTrue();
        assertThat(schemas.has("ContactGroupMemberResponse")).isTrue();

        JsonNode addMember = schemas.at("/AddMemberRequest");
        assertThat(addMember.at("/properties/contactId/format").asText()).isEqualTo("uuid");
        assertThat(addMember.path("required").toString()).contains("contactId");

        JsonNode batchRequest = schemas.at("/BatchMemberRequest");
        assertThat(batchRequest.path("required").toString()).contains("contactIds");
    }

    @Test
    @DisplayName("OAS-4: memberCount is on ContactGroupResponse; no contactGroupId anywhere")
    void memberCountPresentAndNoOwnershipLeak() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode schemas = spec.at("/components/schemas");
        assertThat(schemas.at("/ContactGroupResponse/properties").has("memberCount")).isTrue();
        assertThat(schemas.at("/ContactGroupMemberResponse/properties").has("contactGroupId")).isFalse();
        assertThat(schemas.at("/ContactResponse/properties").has("contactGroupId")).isFalse();
    }

    @Test
    @DisplayName("OAS-5: member list documents page/size/sort/search parameters")
    void listDocumentsPagination() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode params = memberPath(spec, "")
            .at("/get/parameters");
        java.util.Set<String> names = new java.util.HashSet<>();
        params.forEach(p -> names.add(p.path("name").asText()));
        assertThat(names).contains("page", "size", "sort", "search", "id");
    }
}
