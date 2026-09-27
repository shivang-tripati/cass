package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shivang.obd.security.config.OpenApiConfig;
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
 * VB-6C.2 OpenAPI contract verification — the GENERATED specification is
 * the source of truth (same harness as the VB-6B.2 member contract test:
 * springdoc metadata endpoint over a real application context). Asserts
 * that {@code dailyDialLimit} is documented with its validation bounds,
 * nullability, and description across the campaign create/update request
 * schemas and the campaign response schema, and that the existing
 * campaign endpoints remain documented.
 */
@org.springframework.boot.test.context.SpringBootTest(webEnvironment =
    org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@Import({CampaignController.class, CampaignService.class, CampaignMapper.class,
    CampaignReadinessService.class, CampaignExecutionService.class,
    CallAttemptService.class, CampaignLifecyclePolicy.class,
    CampaignResourceValidationService.class,
    com.shivang.obd.campaign.event.CampaignEventPublisher.class,
    OpenApiConfig.class,
    org.springdoc.core.configuration.SpringDocConfiguration.class,
    org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
    org.springdoc.core.configuration.SpringDocJavadocConfiguration.class})
class CampaignOpenApiContractTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fetchOpenApi() throws Exception {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode schema(JsonNode spec, String name) {
        return spec.at("/components/schemas/" + name);
    }

    private JsonNode dailyDialLimitProperty(JsonNode schemaNode) {
        return schemaNode.at("/properties/dailyDialLimit");
    }

    @Test
    @DisplayName("OAS-C1: dailyDialLimit is on the create schema with bounds and nullability")
    void createSchemaDocumentsDailyDialLimit() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode create = schema(spec, "CreateCampaignRequest");
        assertThat(create.isMissingNode()).isFalse();

        JsonNode field = dailyDialLimitProperty(create);
        assertThat(field.isMissingNode()).isFalse();
        assertThat(field.path("type").asText()).isEqualTo("integer");
        assertThat(field.path("minimum").asInt()).isEqualTo(1);
        assertThat(field.path("maximum").asInt()).isEqualTo(3);
        // Nullable: omitted/null = platform maximum (never "required").
        assertThat(create.path("required").toString()).doesNotContain("dailyDialLimit");
        assertThat(field.path("description").asText())
            .contains("Voice Blast")
            .contains("1-3")
            .contains("3");
    }

    @Test
    @DisplayName("OAS-C2: dailyDialLimit is on the update schema with the same bounds")
    void updateSchemaDocumentsDailyDialLimit() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode update = schema(spec, "UpdateCampaignRequest");
        assertThat(update.isMissingNode()).isFalse();

        JsonNode field = dailyDialLimitProperty(update);
        assertThat(field.isMissingNode()).isFalse();
        assertThat(field.path("type").asText()).isEqualTo("integer");
        assertThat(field.path("minimum").asInt()).isEqualTo(1);
        assertThat(field.path("maximum").asInt()).isEqualTo(3);
        assertThat(update.path("required").toString()).doesNotContain("dailyDialLimit");
    }

    @Test
    @DisplayName("OAS-C3: dailyDialLimit is on the response schema, nullable, described")
    void responseSchemaDocumentsDailyDialLimit() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode response = schema(spec, "CampaignResponse");
        assertThat(response.isMissingNode()).isFalse();

        JsonNode field = dailyDialLimitProperty(response);
        assertThat(field.isMissingNode()).isFalse();
        assertThat(field.path("type").asText()).isEqualTo("integer");
        assertThat(field.path("description").asText())
            .contains("Null uses the platform maximum of 3");
    }

    @Test
    @DisplayName("OAS-C4: campaign create/update operations remain documented (paths + 400 model)")
    void campaignOperationsRemainDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode createOp = spec.at("/paths/~1api~1v1~1campaigns/post");
        JsonNode updateOp = spec.at("/paths/~1api~1v1~1campaigns~1{id}/put");
        assertThat(createOp.isMissingNode()).isFalse();
        assertThat(updateOp.isMissingNode()).isFalse();

        // The documented 400 validation-error response model persists.
        assertThat(createOp.at("/responses/400").isMissingNode()).isFalse();
        assertThat(updateOp.at("/responses/400").isMissingNode()).isFalse();
        // The requestBody still references the (now extended) create schema.
        // NOTE: the media-type segment "application/json" contains a '/', which a
        // JSON Pointer must escape as '~1' (RFC 6901) - hence "application~1json".
        assertThat(createOp.at("/requestBody/content/application~1json/schema/$ref")
            .asText()).contains("CreateCampaignRequest");
        assertThat(updateOp.at("/requestBody/content/application~1json/schema/$ref")
            .asText()).contains("UpdateCampaignRequest");
    }
}
