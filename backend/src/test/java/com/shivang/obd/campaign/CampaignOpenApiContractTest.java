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

    // === VB-6D.2: retry rule documentation in the GENERATED spec ===

    @Test
    @DisplayName("OAS-D1: RetryPolicyConfig documents the flat allowance and the rules array")
    void retryPolicySchemaDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode policy = schema(spec, "RetryPolicyConfig");
        assertThat(policy.isMissingNode()).isFalse();

        // The pre-existing flat fields are still documented (not replaced).
        assertThat(policy.at("/properties/maxAttempts/properties").isMissingNode()).isTrue();
        assertThat(policy.at("/properties/maxAttempts/type").asText()).isEqualTo("integer");
        assertThat(policy.at("/properties/maxAttempts/minimum").asInt()).isZero();
        assertThat(policy.at("/properties/maxAttempts/maximum").asInt()).isEqualTo(10);
        assertThat(policy.at("/properties/intervalSeconds/type").asText()).isEqualTo("integer");
        assertThat(policy.at("/properties/strategy").isMissingNode()).isFalse();

        // maxAttempts counts RETRIES: the description must say so, because the
        // field name is the single most misreadable part of the policy.
        assertThat(policy.at("/properties/maxAttempts/description").asText())
                .contains("RETRIES")
                .contains("1 + maxAttempts");

        JsonNode rules = policy.at("/properties/rules");
        assertThat(rules.isMissingNode()).isFalse();
        assertThat(rules.at("/type").asText()).isEqualTo("array");
        assertThat(rules.at("/items/$ref").asText()).contains("RetryRuleConfig");
    }

    @Test
    @DisplayName("OAS-D2: RetryRuleConfig documents category, count bounds and the MM:SS delay")
    void retryRuleSchemaDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode rule = schema(spec, "RetryRuleConfig");
        assertThat(rule.isMissingNode()).isFalse();

        JsonNode category = rule.at("/properties/category");
        assertThat(category.isMissingNode()).isFalse();
        assertThat(category.at("/type").asText()).isEqualTo("string");
        // All six product categories are enumerated, including the two reserved
        // for a future reliable provider mapping.
        assertThat(category.at("/enum").toString())
                .contains("NO_ANSWER", "BUSY", "HANGUP", "FAILED",
                        "SWITCHED_OFF", "NOT_REACHABLE");
        assertThat(category.at("/description").asText())
                .contains("SWITCHED_OFF", "NOT_REACHABLE")
                .contains("HANGUP");

        JsonNode maxRetries = rule.at("/properties/maxRetries");
        assertThat(maxRetries.at("/type").asText()).isEqualTo("integer");
        assertThat(maxRetries.at("/minimum").asInt()).isZero();
        assertThat(maxRetries.at("/maximum").asInt()).isEqualTo(10);
        assertThat(maxRetries.at("/description").asText())
                .contains("RETRIES")
                .contains("never consume");

        JsonNode delay = rule.at("/properties/retryDelay");
        assertThat(delay.at("/type").asText()).isEqualTo("string");
        assertThat(delay.at("/pattern").asText()).isEqualTo("^\\d{2}:\\d{2}$");
        assertThat(delay.at("/description").asText())
                .contains("MM:SS")
                .contains("timezone-independent");

        // No rule field is mandatory in the schema sense beyond what the domain
        // validator enforces: an omitted delay is how a category is switched off.
        assertThat(rule.path("required").toString())
                .contains("category")
                .doesNotContain("retryDelay");
    }

    @Test
    @DisplayName("OAS-D3: campaign create/update request schemas expose retryPolicy")
    void campaignRequestsExposeRetryPolicy() throws Exception {
        JsonNode spec = fetchOpenApi();
        for (String dto : new String[] {"CreateCampaignRequest", "UpdateCampaignRequest",
                "CampaignResponse"}) {
            assertThat(schema(spec, dto).at("/properties/retryPolicy").isMissingNode())
                    .as("%s must document retryPolicy", dto)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("OAS-D4: the previously documented campaign fields are unchanged")
    void existingCampaignFieldsUnchanged() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode create = schema(spec, "CreateCampaignRequest");

        // dailyDialLimit (VB-6C.2) must survive this phase untouched.
        JsonNode daily = create.at("/properties/dailyDialLimit");
        assertThat(daily.isMissingNode()).isFalse();
        assertThat(daily.at("/type").asText()).isEqualTo("integer");
        assertThat(daily.at("/minimum").asInt()).isEqualTo(1);
        assertThat(daily.at("/maximum").asInt()).isEqualTo(3);
    }

    // === VB-6D.3: campaign daily-attempt ceiling documentation ===

    @Test
    @DisplayName("OAS-E1: maxDailyAttempts is documented on create/update/response")
    void maxDailyAttemptsExposedOnAllCampaignSchemas() throws Exception {
        JsonNode spec = fetchOpenApi();
        for (String dto : new String[] {"CreateCampaignRequest", "UpdateCampaignRequest",
                "CampaignResponse"}) {
            JsonNode field = schema(spec, dto).at("/properties/maxDailyAttempts");
            assertThat(field.isMissingNode())
                    .as("%s must document maxDailyAttempts", dto)
                    .isFalse();
            assertThat(field.at("/type").asText()).isEqualTo("integer");
            assertThat(field.at("/minimum").asInt())
                    .isEqualTo(DailyAttemptSafetyService.MIN_DAILY_ATTEMPTS_PER_CONTACT);
            assertThat(field.at("/maximum").asInt())
                    .isEqualTo(DailyAttemptSafetyService.MAX_DAILY_ATTEMPTS_PER_CONTACT);
        }
    }

    @Test
    @DisplayName("OAS-E2: the description distinguishes the two daily limits and states null semantics")
    void maxDailyAttemptsDescriptionIsUnambiguous() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode field =
                schema(spec, "CreateCampaignRequest").at("/properties/maxDailyAttempts");
        String description = field.at("/description").asText();

        // The single most dangerous documentation mistake here would be
        // letting a reader confuse this with the DNID-scoped dial limit.
        assertThat(description)
                .contains("ATTEMPT")
                .contains("dailyDialLimit")
                .contains("DNID")
                .contains("default of 10");
        // Null means platform default, and it is not required.
        assertThat(schema(spec, "CreateCampaignRequest").path("required").toString())
                .doesNotContain("maxDailyAttempts");
    }

    @Test
    @DisplayName("OAS-E3: both daily controls are documented side by side on the same schema")
    void bothDailyLimitsCoexistOnTheCreateSchema() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode create = schema(spec, "CreateCampaignRequest");

        assertThat(create.at("/properties/dailyDialLimit").isMissingNode()).isFalse();
        assertThat(create.at("/properties/maxDailyAttempts").isMissingNode()).isFalse();

        // dailyDialLimit keeps the VB-6C ceiling of 3 (provider-accepted dials).
        assertThat(create.at("/properties/dailyDialLimit/maximum").asInt())
                .isEqualTo(DailyDialLimitService.PLATFORM_DAILY_DIAL_LIMIT);
        // maxDailyAttempts is the campaign's dispatch ceiling, bounded by 10.
        assertThat(create.at("/properties/maxDailyAttempts/maximum").asInt())
                .isEqualTo(DailyAttemptSafetyService.MAX_DAILY_ATTEMPTS_PER_CONTACT);
    }
}
