package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shivang.obd.security.config.OpenApiConfig;
import com.shivang.obd.voice.agent.AgentRingWindow;
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
    @DisplayName("OAS-VB8H: CampaignExecutionResponse documents the derived deferred reason (B10)")
    void executionDeferredReasonIsDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode response = schema(spec, "CampaignExecutionResponse");
        assertThat(response.isMissingNode()).isFalse();

        assertThat(response.at("/properties/deferredReason/type").asText())
                .as("nullable, because it only applies while the execution is REQUESTED")
                .isIn("string", "");
        assertThat(response.at("/properties/deferredReason/description").asText().toLowerCase())
                .as("the description must say it is derived and never persisted")
                .contains("derived")
                .contains("never persisted");

        // It must not be confusable with the failure reason.
        assertThat(response.at("/properties/failureReason").isMissingNode()).isFalse();
        assertThat(response.at("/properties/deferredReason/description").asText())
                .contains("failureReason");
    }

    // VB-8J: this method had @DisplayName but no @Test, so the one guard on the
    // campaign status enum never ran. Restored, because VB-8J changes which
    // statuses are legal and this is the assertion that would notice.
    @Test
    @DisplayName("OAS-VB8H: the campaign status enum is unchanged (no lifecycle API churn)")
    void campaignStatusEnumUnchanged() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode status = schema(spec, "CampaignExecutionResponse")
                .at("/properties/status");
        assertThat(status.at("/enum/0").asText()).isNotBlank();
        assertThat(status.at("/enum").size()).isEqualTo(5);
    }

    @Test
    @DisplayName("OAS-VB8J: the status endpoint documents reserved states, not engine-driven ones")
    void statusEndpointDocumentsReservedTargets() throws Exception {
        JsonNode spec = fetchOpenApi();

        String path = "/paths/~1api~1v1~1campaigns~1{id}~1status/patch";
        assertThat(spec.at(path).isMissingNode())
                .as("the status route must still exist, unchanged in shape")
                .isFalse();

        String conflict = spec.at(path + "/responses/409/description").asText();

        // VB-8J removed the "engine-driven" attribution: no engine path writes
        // campaign status, and none should, because campaign status is never
        // derived from executions. The generated document is the source of
        // truth, so it must not keep asserting the old reason.
        assertThat(conflict)
                .as("409 must name the reserved states")
                .contains("RUNNING", "COMPLETED", "FAILED");
        assertThat(conflict)
                .as("and must not claim an execution engine performs them")
                .doesNotContain("engine-driven");

        String description = spec.at(path + "/description").asText();
        assertThat(description)
                .as("PAUSED -> RUNNING is no longer an operator transition")
                .doesNotContain("PAUSED -> RUNNING");
        assertThat(description)
                .as("and the reserved rationale must be stated")
                .contains("reserved");
    }

    @Test
    @DisplayName("OAS-VB8J: execution creation documents the duplicate in-progress refusal")
    void executionCreationDocumentsDuplicateRefusal() throws Exception {
        JsonNode spec = fetchOpenApi();

        String path = "/paths/~1api~1v1~1campaigns~1{id}~1executions/post";
        assertThat(spec.at(path).isMissingNode())
                .as("the execution route must still exist, unchanged in shape")
                .isFalse();

        String conflict = spec.at(path + "/responses/409/description").asText();
        // A campaign may have only one in-flight execution, otherwise each
        // execution materialises an attempt for every contact and the same
        // contact is dialled once per execution.
        assertThat(conflict)
                .as("the pre-existing idempotency-key 409 must still be documented")
                .contains("idempotency");
        assertThat(conflict)
                .as("and the VB-8J refusal must be discoverable too")
                .contains("already has an execution in progress");
    }

    // Unchanged, but still dead: this method has @DisplayName and no @Test.
    // Left as found because it guards RetryPolicyConfig, not anything VB-8J
    // touched. Reported rather than silently fixed.
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

    // === VB-6E: maximum call duration documentation ===

    @Test
    @DisplayName("OAS-F1: maxCallDurationSeconds is documented on create/update/response")
    void maxCallDurationExposedOnAllCampaignSchemas() throws Exception {
        JsonNode spec = fetchOpenApi();
        for (String dto : new String[] {"CreateCampaignRequest", "UpdateCampaignRequest",
                "CampaignResponse"}) {
            JsonNode field = schema(spec, dto).at("/properties/maxCallDurationSeconds");
            assertThat(field.isMissingNode())
                    .as("%s must document maxCallDurationSeconds", dto)
                    .isFalse();
            assertThat(field.at("/type").asText()).isEqualTo("integer");
            assertThat(field.at("/minimum").asInt())
                    .isEqualTo(MaxCallDurationPolicy.MIN_MAX_CALL_DURATION_SECONDS);
            assertThat(field.at("/maximum").asInt())
                    .isEqualTo(MaxCallDurationPolicy.MAX_MAX_CALL_DURATION_SECONDS);
        }
    }

    @Test
    @DisplayName("OAS-F2: the description states the default and disambiguates the three timeouts")
    void maxCallDurationDescriptionIsUnambiguous() throws Exception {
        JsonNode spec = fetchOpenApi();
        String description = schema(spec, "CreateCampaignRequest")
                .at("/properties/maxCallDurationSeconds/description").asText();

        // The dangerous misreading is confusing this with a ring timeout, a
        // provider connection timeout, or the audio length. The description has
        // to rule all three out explicitly.
        assertThat(description)
                .contains("ESTABLISHED")
                .contains("300")
                .contains("ring timeout")
                .contains("connection timeout")
                .contains("playback length");

        // Null means the platform default, and the field is optional.
        assertThat(schema(spec, "CreateCampaignRequest").path("required").toString())
                .doesNotContain("maxCallDurationSeconds");
    }

    @Test
    @DisplayName("OAS-F3: all three call-timing ceilings coexist on the create schema")
    void allCallTimingFieldsCoexist() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode create = schema(spec, "CreateCampaignRequest");

        // The three VB-6C/VB-6D/VB-6E daily or duration controls must be
        // independently documented so a client cannot confuse them.
        assertThat(create.at("/properties/dailyDialLimit").isMissingNode()).isFalse();
        assertThat(create.at("/properties/maxDailyAttempts").isMissingNode()).isFalse();
        assertThat(create.at("/properties/maxCallDurationSeconds").isMissingNode()).isFalse();

        assertThat(create.at("/properties/dailyDialLimit/maximum").asInt()).isEqualTo(3);
        assertThat(create.at("/properties/maxDailyAttempts/maximum").asInt()).isEqualTo(10);
        assertThat(create.at("/properties/maxCallDurationSeconds/maximum").asInt()).isEqualTo(3600);
    }

    // === VB-7A: CONNECT_BY_AGENT campaign configuration documentation ===

    @Test
    @DisplayName("OAS-7A1: the create schema documents the CONNECT_BY_AGENT configuration: "
            + "queue reference, selection strategy, and ring window")
    void connectByAgentConfigurationIsDocumentedOnCreate() throws Exception {
        JsonNode field = schema(fetchOpenApi(), "CreateCampaignRequest")
                .at("/properties/typeConfig");
        assertThat(field.isMissingNode()).isFalse();

        String description = field.at("/description").asText();
        // The queue reference.
        assertThat(description).contains("connectByAgent.queueId");
        // The selection strategy, and the truth that only one exists.
        assertThat(description).contains("connectByAgent.selectionStrategy")
                .contains("LEAST_ACTIVE_RESERVATIONS");
        // The ring window and its bounds.
        assertThat(description).contains("connectByAgent.ringDurationSeconds").contains("10-240");
        // The queue is a reference, not a copy of agent state.
        assertThat(description).contains("owned by this").contains("tenant");
    }

    @Test
    @DisplayName("OAS-7A2: the create schema states that agent availability is NOT a readiness "
            + "condition while the queue's administrative status is")
    void availabilityIsDocumentedAsNotAReadinessCondition() throws Exception {
        String description = schema(fetchOpenApi(), "CreateCampaignRequest")
                .at("/properties/typeConfig/description").asText();

        // The configuration-vs-runtime distinction is the single most
        // consequential thing an integrator must not get wrong here, so it is
        // documented rather than left to be inferred.
        assertThat(description)
                .contains("Live agent")
                .contains("availability is NOT a readiness condition")
                .contains("administrative status IS");
    }

    @Test
    @DisplayName("OAS-7A3: the update and response schemas document the same shape and the "
            + "snapshot guarantee")
    void connectByAgentConfigurationIsDocumentedOnUpdateAndResponse() throws Exception {
        JsonNode spec = fetchOpenApi();

        String update = schema(spec, "UpdateCampaignRequest")
                .at("/properties/typeConfig/description").asText();
        assertThat(update)
                .contains("connectByAgent.queueId")
                .contains("connectByAgent.selectionStrategy")
                .contains("connectByAgent.ringDurationSeconds");
        assertThat(update).contains("immutable configuration snapshot");

        String response = schema(spec, "CampaignResponse")
                .at("/properties/typeConfig/description").asText();
        assertThat(response)
                .contains("connectByAgent.queueId")
                .contains("LEAST_ACTIVE_RESERVATIONS")
                .contains("10-240");
        // Runtime facts must not be promised in a configuration payload.
        assertThat(response).contains("runtime facts and are never returned");
    }

    @Test
    @DisplayName("OAS-7A4: the documented example is the exact contract shape and parses as valid "
            + "typed configuration")
    void documentedExampleIsAValidConfiguration() throws Exception {
        JsonNode example = schema(fetchOpenApi(), "CreateCampaignRequest")
                .at("/properties/typeConfig/example");
        assertThat(example.isMissingNode()).isFalse();
        // The field is an untyped JSON object, so springdoc emits the example as
        // an inline object rather than a quoted string.
        assertThat(example.isObject()).isTrue();

        var parsed = (com.shivang.obd.campaign.config.ConnectByAgentCampaignConfig)
                com.shivang.obd.campaign.config.CampaignTypeConfig.fromTypeConfig(
                        CampaignType.CONNECT_BY_AGENT, example);

        assertThat(parsed.queueId()).isNotNull();
        assertThat(parsed.selectionStrategy())
                .isEqualTo(com.shivang.obd.campaign.config.AgentSelectionStrategy
                        .LEAST_ACTIVE_RESERVATIONS);
        assertThat(parsed.effectiveRingSeconds())
                .isEqualTo(AgentRingWindow.DEFAULT_RING_SECONDS);
    }

    @Test
    @DisplayName("OAS-7A5: no new endpoint was introduced; the existing operations, 400 contract "
            + "and bearer security are unchanged")
    void noNewEndpointAndSecurityUnchanged() throws Exception {
        JsonNode spec = fetchOpenApi();

        // Configuration stays part of the campaign configuration contract.
        assertThat(spec.at("/paths/~1api~1v1~1campaigns/post").isMissingNode()).isFalse();
        assertThat(spec.at("/paths/~1api~1v1~1campaigns~1{id}/put").isMissingNode()).isFalse();
        assertThat(spec.at("/paths/~1api~1v1~1campaigns/post").at("/responses/400")
                .isMissingNode())
                .as("invalid CONNECT_BY_AGENT configuration uses the existing 400 contract")
                .isFalse();

        // No /campaigns/{id}/agents resource was created.
        assertThat(spec.at("/paths").toString()).doesNotContain("~1agents");

        // Bearer security is unchanged on the campaign operations.
        JsonNode createOp = spec.at("/paths/~1api~1v1~1campaigns/post");
        assertThat(createOp.at("/security/0/bearerAuth").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("OAS-7A6: the VB-6C.2/6D.3/6E campaign fields are untouched by this phase")
    void priorPhaseFieldsSurvive() throws Exception {
        JsonNode create = schema(fetchOpenApi(), "CreateCampaignRequest");
        assertThat(create.at("/properties/dailyDialLimit/isMissingNode").isMissingNode())
                .isTrue();
        assertThat(create.at("/properties/dailyDialLimit").isMissingNode()).isFalse();
        assertThat(create.at("/properties/maxDailyAttempts").isMissingNode()).isFalse();
        assertThat(create.at("/properties/maxCallDurationSeconds").isMissingNode()).isFalse();
        // and the ring window documented here is not the max-duration field
        assertThat(create.at("/properties/maxCallDurationSeconds/maximum").asInt())
                .isEqualTo(3600);
        assertThat(AgentRingWindow.MAX_RING_SECONDS).isLessThan(3600);
    }

    // === VB-7B: MISSED_CALL campaign configuration documentation ===

    @Test
    @DisplayName("OAS-7B1: the generated document's campaignType enum exposes MISSED_CALL "
            + "alongside every pre-existing type")
    void missedCallAppearsInTheGeneratedEnum() throws Exception {
        JsonNode spec = fetchOpenApi();
        // springdoc inlines the enum on the property rather than emitting a
        // standalone component, so the source of truth is the property itself.
        JsonNode campaignType = spec.at("/components/schemas/CampaignResponse/properties/campaignType");
        assertThat(campaignType.isMissingNode()).isFalse();
        assertThat(campaignType.at("/type").asText()).isEqualTo("string");
        assertThat(campaignType.at("/enum").toString())
                .contains("MISSED_CALL")
                .contains("PLAYFILE")
                .contains("DTMF")
                .contains("CONNECT_BY_AGENT");
        // and the create request exposes the same four, not a stale three
        assertThat(spec.at("/components/schemas/CreateCampaignRequest"
                + "/properties/campaignType/enum").toString()).contains("MISSED_CALL");
    }

    @Test
    @DisplayName("OAS-7B2: the create schema documents the MISSED_CALL ring budget with its "
            + "bounds")
    void missedCallRingBudgetIsDocumentedOnCreate() throws Exception {
        String description = schema(fetchOpenApi(), "CreateCampaignRequest")
                .at("/properties/typeConfig/description").asText();

        assertThat(description)
                .contains("MISSED_CALL")
                .contains("missedCall.ringDurationSeconds")
                .contains("10-60")
                .contains("rebas");
    }

    @Test
    @DisplayName("OAS-7B3: the create schema states that MISSED_CALL plays nothing and owns no "
            + "agent, queue or input configuration")
    void missedCallCarriesNoMediaAgentOrInput() throws Exception {
        String description = schema(fetchOpenApi(), "CreateCampaignRequest")
                .at("/properties/typeConfig/description").asText();

        assertThat(description)
                .contains("plays no media")
                .contains("no agent or queue")
                .contains("DID, audience, retry, schedule and safety");
    }

    @Test
    @DisplayName("OAS-7B4: the create schema states the answered-call behaviour and that a "
            + "completed ring is not retried")
    void answeredCallBehaviourIsDocumented() throws Exception {
        String description = schema(fetchOpenApi(), "CreateCampaignRequest")
                .at("/properties/typeConfig/description").asText();

        // The two semantics an integrator most easily gets wrong, documented
        // rather than left to be inferred from the code.
        assertThat(description)
                .contains("rebased onto the answer instant")
                .contains("is not retried")
                .contains("carrier-reported no-answer remains an ordinary retryable NO_ANSWER");
    }

    @Test
    @DisplayName("OAS-7B5: the update and response schemas document the same MISSED_CALL shape")
    void missedCallIsDocumentedOnUpdateAndResponse() throws Exception {
        JsonNode spec = fetchOpenApi();

        String update = schema(spec, "UpdateCampaignRequest")
                .at("/properties/typeConfig/description").asText();
        // Asserted on phrases that do not straddle a line break in the rendered
        // description - the point is the wording, not the wrapping.
        assertThat(update).contains("MISSED_CALL").contains("missedCall.ringDurationSeconds")
                .contains("10-60").contains("collects no input");

        String response = schema(spec, "CampaignResponse")
                .at("/properties/typeConfig/description").asText();
        assertThat(response)
                .contains("MISSED_CALL")
                .contains("missedCall.ringDurationSeconds")
                .contains("10-60");
    }

    @Test
    @DisplayName("OAS-7B6: the documented bounds are the shared authority's bounds")
    void documentedBoundsMatchTheAuthority() throws Exception {
        String description = schema(fetchOpenApi(), "CreateCampaignRequest")
                .at("/properties/typeConfig/description").asText();
        assertThat(description).contains(
                com.shivang.obd.campaign.config.MissedCallRingWindow.MIN_RING_SECONDS
                        + "-" + com.shivang.obd.campaign.config.MissedCallRingWindow
                                .MAX_RING_SECONDS);
    }

    @Test
    @DisplayName("OAS-7B7: no endpoint, security or error contract changed in VB-7B")
    void noApiSurfaceChangeInVb7b() throws Exception {
        JsonNode spec = fetchOpenApi();

        // Same operations, same 400 contract, same bearer security as VB-7A.
        assertThat(spec.at("/paths/~1api~1v1~1campaigns/post/responses/400").isMissingNode())
                .isFalse();
        assertThat(spec.at("/paths/~1api~1v1~1campaigns/post/security/0/bearerAuth")
                .isMissingNode()).isFalse();
        assertThat(spec.at("/paths").toString()).doesNotContain("~1agents");
        assertThat(spec.at("/paths").toString()).doesNotContain("~1missed");
    }

    @Test
    @DisplayName("OAS-7B8: the MISSED_CALL example on the response schema is a valid typed "
            + "configuration")
    void documentedMissedCallExampleParses() throws Exception {
        // Built from the same shape the schema documents, then parsed by the
        // authoritative parser: the docs and the contract cannot disagree.
        var example = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree("{\"missedCall\": {\"ringDurationSeconds\": 30}}");
        var parsed = (com.shivang.obd.campaign.config.MissedCallCampaignConfig)
                com.shivang.obd.campaign.config.CampaignTypeConfig.fromTypeConfig(
                        CampaignType.MISSED_CALL, example);
        assertThat(parsed.effectiveRingSeconds())
                .isEqualTo(com.shivang.obd.campaign.config.MissedCallRingWindow
                        .DEFAULT_RING_SECONDS);
    }

    // === VB-8B: the manual attempt endpoint's frozen-configuration contract ===

    @Test
    @DisplayName("OAS-8B-1: the manual attempt endpoint documents the frozen-configuration boundary")
    void manualAttemptDocumentsTheFrozenBoundary() throws Exception {
        JsonNode spec = fetchOpenApi();

        String path = "/paths/~1api~1v1~1campaigns~1{campaignId}~1executions"
                + "~1{executionId}~1attempts/post";
        assertThat(spec.at(path).isMissingNode())
                .as("the manual attempt route must still exist, unchanged in shape")
                .isFalse();

        String description = spec.at(path + "/description").asText();

        // The endpoint's contract genuinely changed in VB-8B: the request can no
        // longer override execution configuration. The generated document is the
        // source of truth, so it must say so.
        assertThat(description)
                .as("the operation must state that the snapshot is authoritative")
                .contains("snapshot");
        assertThat(description)
                .as("and that a campaign edit cannot change what the call dials")
                .contains("cannot change what this call");
        assertThat(description)
                .as("and that a terminal execution cannot be resurrected")
                .contains("terminal execution");

        // The new 409 causes must be discoverable, not just the old duplicate one.
        String conflict = spec.at(path + "/responses/409/description").asText();
        assertThat(conflict).contains("terminal execution");
        assertThat(conflict).contains("didId");
        assertThat(conflict).contains("attempt number");

        // And the shape is unchanged: same path, same request fields.
        JsonNode ref = spec.at(path + "/requestBody/content/"
                + "application~1json/schema/$ref");
        assertThat(ref.asText())
                .as("the request schema reference is unchanged - no new API version")
                .isNotBlank();
        String dto = ref.asText().substring(ref.asText().lastIndexOf('/') + 1);
        for (String field : new String[] {"contactId", "didId", "attemptNumber", "scheduledAt"}) {
            assertThat(spec.at("/components/schemas/" + dto + "/properties/" + field)
                    .isMissingNode())
                    .as("%s.%s must still exist: no field was renamed or removed", dto, field)
                    .isFalse();
        }
    }

    // === VB-7C.3: the snapshot hardening is invisible to the public API ===

    @Test
    @DisplayName("OAS-7C.3-1: nothing about the execution snapshot reaches the public document")
    void snapshotStaysInternalToThePublicApi() throws Exception {
        JsonNode spec = fetchOpenApi();
        String document = spec.toString();

        // The snapshot gained a field this phase. It is execution-owned internal
        // state, so none of it may appear anywhere in the generated document -
        // not as a schema, not as a property, not inside a description.
        for (String leaked : new String[] {
                "CampaignConfigurationSnapshot",
                "CampaignExecutionConfiguration",
                "integration_config",
                "asIntegrationConfig",
                "retry_rules",
                "max_call_duration_seconds"}) {
            assertThat(document.contains(leaked))
                    .as("the generated document must not mention %s", leaked)
                    .isFalse();
        }

        // No generated schema is a snapshot carrier either.
        assertThat(spec.at("/components/schemas").propertyNames())
                .as("no execution-snapshot schema may be generated")
                .noneMatch(name -> name.contains("Snapshot")
                        || name.contains("ExecutionConfiguration"));
    }

    @Test
    @DisplayName("OAS-7C.3-2: the public campaign schemas are exactly what VB-7C.2 defined")
    void publicCampaignSchemasAreUnchanged() throws Exception {
        JsonNode spec = fetchOpenApi();

        // The integration configuration is still exposed the way VB-7C.2
        // exposed it - as a typed block on the campaign DTOs. Freezing it must
        // not have changed how it is presented.
        for (String dto : new String[] {"CreateCampaignRequest", "UpdateCampaignRequest",
                "CampaignResponse"}) {
            assertThat(spec.at("/components/schemas/" + dto
                    + "/properties/integrationConfig").isMissingNode())
                    .as("%s still exposes integrationConfig", dto)
                    .isFalse();
        }

        // ... and it was not flattened into loose top-level webhook/privacy
        // properties, which is the shape a leak would take.
        for (String dto : new String[] {"CreateCampaignRequest", "UpdateCampaignRequest",
                "CampaignResponse"}) {
            for (String loose : new String[] {"webhook", "reportPrivacy", "webhookEndpoint",
                    "selectedEvents", "privacyPolicy"}) {
                assertThat(spec.at("/components/schemas/" + dto + "/properties/" + loose)
                        .isMissingNode())
                        .as("%s must not flatten the integration config into %s", dto, loose)
                        .isTrue();
            }
        }
    }

    // === VB-7C.2: integration configuration contract ===

    @Test
    @DisplayName("OAS-7C.2-1: the generated document exposes a typed integrationConfig on "
            + "create, update and response")
    void integrationConfigIsTypedOnEveryCampaignSchema() throws Exception {
        JsonNode spec = fetchOpenApi();

        for (String dto : new String[] {"CreateCampaignRequest", "UpdateCampaignRequest",
                "CampaignResponse"}) {
            JsonNode property = spec.at("/components/schemas/" + dto
                    + "/properties/integrationConfig");
            assertThat(property.isMissingNode())
                    .as("%s must declare integrationConfig", dto)
                    .isFalse();
            // A typed object, NOT the free-form JSON the field used to be.
            // springdoc emits a $ref to a generated component, so it is resolved
            // before the shape is asserted.
            assertThat(property.at("/$ref").asText())
                    .as("%s.integrationConfig must reference a typed schema", dto)
                    .isNotBlank();
            JsonNode resolved = resolve(spec, property);
            assertThat(resolved.at("/type").asText())
                    .as("%s.integrationConfig must be an object, not an arbitrary blob", dto)
                    .isEqualTo("object");
            assertThat(resolved.at("/properties/webhook").isMissingNode())
                    .as("%s.integrationConfig must describe the webhook block", dto)
                    .isFalse();
            assertThat(resolved.at("/properties/reportPrivacy").isMissingNode())
                    .as("%s.integrationConfig must describe the report privacy block", dto)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("OAS-7C.2-2: the generated document describes the webhook and reportPrivacy "
            + "structure")
    void integrationConfigStructureIsDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();

        String configSchema = firstSchemaNameContaining(spec, "CampaignIntegrationConfig");
        assertThat(configSchema)
                .as("a CampaignIntegrationConfig schema must be generated")
                .isNotNull();

        JsonNode schema = spec.at("/components/schemas/" + configSchema);
        assertThat(schema.at("/properties/webhook").isMissingNode()).isFalse();
        assertThat(schema.at("/properties/reportPrivacy").isMissingNode()).isFalse();

        JsonNode webhook = resolve(spec, schema.at("/properties/webhook"));
        assertThat(webhook.at("/properties/enabled").isMissingNode()).isFalse();
        assertThat(webhook.at("/properties/endpoint").isMissingNode()).isFalse();
        assertThat(webhook.at("/properties/events").isMissingNode()).isFalse();

        JsonNode privacy = resolve(spec, schema.at("/properties/reportPrivacy"));
        assertThat(privacy.at("/properties/policy").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("OAS-7C.2-3: the event field documents the PUBLIC vocabulary, and no Java "
            + "enum name is exposed anywhere in the document")
    void eventVocabularyIsPublic() throws Exception {
        String text = fetchOpenApi().toString();

        // The contract's own vocabulary must be discoverable in the document.
        assertThat(text)
                .contains("campaign.attempt.completed")
                .contains("campaign.attempt.failed")
                .contains("campaign.attempt.cancelled");

        // And no internal constant may appear in the public contract.
        for (String internal : new String[] {"ATTEMPT_COMPLETED", "ATTEMPT_FAILED",
                "ATTEMPT_CANCELLED", "CAMPAIGN_CREATED", "CAMPAIGN_STATUS_CHANGED"}) {
            assertThat(text)
                    .as("internal name %s must not appear in the generated document", internal)
                    .doesNotContain(internal);
        }
    }

    @Test
    @DisplayName("OAS-7C.2-4: the report privacy policy documents its supported values")
    void privacyPolicyValuesAreDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        // springdoc does not emit a standalone component for the enum, so the
        // documented values are asserted where they are consumed.
        JsonNode policy = spec.at("/components/schemas/ReportPrivacyConfig/properties/policy");
        assertThat(policy.isMissingNode())
                .as("ReportPrivacyConfig.policy must be documented")
                .isFalse();
        assertThat(policy.at("/type").asText()).isEqualTo("string");
        assertThat(policy.at("/enum").toString())
                .as("both supported policies must be listed, in their public form")
                .contains("FULL")
                .contains("MASKED");
    }

    @Test
    @DisplayName("OAS-7C.2-5: the documentation states that delivery is NOT implemented, so a "
            + "reader cannot mistake configuration for a delivery guarantee")
    void deliveryIsDocumentedAsUnimplemented() throws Exception {
        String description = fetchOpenApi().at("/components/schemas/CreateCampaignRequest"
                + "/properties/integrationConfig/description").asText();

        assertThat(description)
                .as("the contract must not imply a webhook is delivered")
                .contains("no webhook is delivered")
                .contains("not a delivery guarantee");
        assertThat(description).contains("no report is generated");
    }

    @Test
    @DisplayName("OAS-7C.2-6: the existing attempt-listing contract is UNCHANGED - report "
            + "privacy is configuration only")
    void attemptListingContractUnchanged() throws Exception {
        JsonNode spec = fetchOpenApi();

        // The attempt response still exposes its contact reference exactly as
        // before: this phase adds configuration and filters nothing.
        JsonNode attemptSchema = spec.at("/components/schemas/CallAttemptResponse");
        assertThat(attemptSchema.isMissingNode())
                .as("CallAttemptResponse must still exist")
                .isFalse();
        assertThat(attemptSchema.at("/properties/contactId").isMissingNode())
                .as("attempt contact visibility is unchanged by VB-7C.2")
                .isFalse();
        // And report privacy must not have leaked into it.
        assertThat(attemptSchema.toString())
                .doesNotContain("reportPrivacy")
                .doesNotContain("privacyPolicy");
    }

    @Test
    @DisplayName("OAS-7C.2-7: no new endpoint was introduced, and the existing security and "
            + "400 contracts are intact")
    void vb7c2NoNewEndpointAndSecurityUnchanged() throws Exception {
        JsonNode spec = fetchOpenApi();

        assertThat(spec.at("/paths/~1api~1v1~1campaigns/post/security/0/bearerAuth")
                .isMissingNode()).isFalse();
        assertThat(spec.at("/paths/~1api~1v1~1campaigns/post/responses/400").isMissingNode())
                .isFalse();
        // No webhook, integration or report resource was added.
        String paths = spec.at("/paths").toString();
        assertThat(paths)
                .doesNotContain("~1webhook")
                .doesNotContain("~1integrations")
                .doesNotContain("~1reports")
                .doesNotContain("~1exports");
    }

    /** Resolves a possibly-{$ref} property to its schema. */
    private JsonNode resolve(JsonNode spec, JsonNode node) {
        JsonNode ref = node.at("/$ref");
        if (ref.isMissingNode()) {
            return node;
        }
        return spec.at("/components/schemas/"
                + ref.asText().replace("#/components/schemas/", ""));
    }

    /** Finds a generated schema whose name contains the given fragment. */
    private String firstSchemaNameContaining(JsonNode spec, String fragment) {
        for (var entry : spec.at("/components/schemas").properties()) {
            if (entry.getKey().contains(fragment)) {
                return entry.getKey();
            }
        }
        return null;
    }
}