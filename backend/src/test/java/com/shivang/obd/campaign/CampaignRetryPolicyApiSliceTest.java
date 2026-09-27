package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shivang.obd.campaign.dto.CampaignResponse;
import com.shivang.obd.campaign.dto.CreateCampaignRequest;
import com.shivang.obd.campaign.dto.UpdateCampaignRequest;
import com.shivang.obd.common.api.response.ResponseFactory;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * VB-6D.2 — REST contract for campaign retry rules.
 *
 * <p>Covers what only the HTTP boundary can prove: that a well-formed rule
 * reaches the service unchanged, that a malformed delay or an out-of-range
 * retry count is rejected with the platform's existing 400
 * {@code VALIDATION_ERROR} ProblemDetail <em>before</em> the service is
 * invoked, and that the response echoes the CONFIGURED rules — including the
 * "no rules" case, which must stay absent rather than becoming an empty object.
 *
 * <p>Semantic rules that bean validation structurally cannot express (a
 * duplicate category, an enabled rule with no delay) are proven against the
 * real service in {@code RetryPolicyValidatorTest} and
 * {@code RetryPolicySnapshotPostgresIntegrationTest#invalidPolicyRejectedAtTheApiBoundary}.
 */
class CampaignRetryPolicyApiSliceTest {

    private static final UUID CAMPAIGN_ID =
            UUID.fromString("d0d0d000-0000-4000-8000-000000000001");
    private static final UUID TENANT_A =
            UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");

    private MockMvc mockMvc;
    private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        campaignService = mock(CampaignService.class);
        CampaignController controller = new CampaignController(
                campaignService,
                mock(CampaignReadinessService.class),
                mock(CampaignExecutionService.class),
                mock(CallAttemptService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.shivang.obd.common.exception.GlobalExceptionHandler())
                .build();
    }

    private CampaignResponse responseForRules(
            com.shivang.obd.campaign.dto.RetryPolicyConfig retry) {
        return new CampaignResponse(
                CAMPAIGN_ID, TENANT_A, "Retry policy", null,
                CampaignType.PLAYFILE, CampaignRunMode.ONE_TIME, CampaignStatus.DRAFT,
                null, null, null, null, ContentMode.AUDIO, null, null,
                null, retry, null, null, null, null, false, null);
    }

    private void stubCreateEchoing() {
        when(campaignService.create(any(CreateCampaignRequest.class), isNull()))
                .thenAnswer(inv -> ResponseFactory.created(
                        responseForRules(inv.<CreateCampaignRequest>getArgument(0).retryPolicy())));
    }

    private void stubUpdateEchoing() {
        when(campaignService.update(eq(CAMPAIGN_ID), any(UpdateCampaignRequest.class)))
                .thenAnswer(inv -> ResponseFactory.ok(
                        responseForRules(inv.<UpdateCampaignRequest>getArgument(1).retryPolicy())));
    }

    private String createBody(String retryJson) {
        return "{\"name\":\"Retry policy\",\"campaignType\":\"PLAYFILE\","
                + "\"retryPolicy\":" + retryJson + "}";
    }

    private String updateBody(String retryJson) {
        return "{\"name\":\"Retry policy\",\"retryPolicy\":" + retryJson + "}";
    }

    private static final String VALID_RULES =
            "\"maxAttempts\":0,\"rules\":["
                    + "{\"category\":\"NO_ANSWER\",\"enabled\":true,"
                    + "\"maxRetries\":3,\"retryDelay\":\"10:00\"},"
                    + "{\"category\":\"BUSY\",\"enabled\":true,"
                    + "\"maxRetries\":1,\"retryDelay\":\"02:00\"}]";

    @Nested
    class Accepted {

        @Test
        @DisplayName("RAPI-1: well-formed rules are accepted and echoed verbatim")
        void rulesAreAcceptedAndEchoed() throws Exception {
            stubCreateEchoing();

            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("{" + VALID_RULES + "}")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.retryPolicy.rules[0].category")
                            .value("NO_ANSWER"))
                    .andExpect(jsonPath("$.data.retryPolicy.rules[0].maxRetries").value(3))
                    .andExpect(jsonPath("$.data.retryPolicy.rules[0].retryDelay").value("10:00"))
                    .andExpect(jsonPath("$.data.retryPolicy.rules[1].category").value("BUSY"))
                    .andExpect(jsonPath("$.data.retryPolicy.rules[1].retryDelay").value("02:00"));

            ArgumentCaptor<CreateCampaignRequest> captor =
                    ArgumentCaptor.forClass(CreateCampaignRequest.class);
            verify(campaignService).create(captor.capture(), isNull());
            var rules = captor.getValue().retryPolicy().rules();
            assertThat(rules).hasSize(2);
            assertThat(rules.get(0).category()).isEqualTo(RetryRuleCategory.NO_ANSWER);
            assertThat(rules.get(0).maxRetries()).isEqualTo(3);
            assertThat(rules.get(0).retryDelay()).isEqualTo("10:00");
        }

        @Test
        @DisplayName("RAPI-2: a disabled rule with no delay is accepted (switch a category off)")
        void disabledRuleWithoutDelayAccepted() throws Exception {
            stubCreateEchoing();

            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("{\"maxAttempts\":0,\"rules\":["
                                    + "{\"category\":\"BUSY\",\"enabled\":false,\"maxRetries\":0}]}")))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("RAPI-3: a campaign with NO rules keeps the pre-VB-6D.2 flat shape")
        void noRulesKeepsLegacyShape() throws Exception {
            stubCreateEchoing();

            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("{\"maxAttempts\":2,\"intervalSeconds\":300}")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.retryPolicy.maxAttempts").value(2))
                    .andExpect(jsonPath("$.data.retryPolicy.intervalSeconds").value(300))
                    .andExpect(jsonPath("$.data.retryPolicy.rules").doesNotExist());
        }

        @Test
        @DisplayName("RAPI-4: an omitted retryPolicy is accepted")
        void omittedRetryPolicyAccepted() throws Exception {
            stubCreateEchoing();

            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"No retry\",\"campaignType\":\"PLAYFILE\"}"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("RAPI-5: UPDATE accepts rules and echoes them")
        void updateAcceptsRules() throws Exception {
            stubUpdateEchoing();

            mockMvc.perform(put("/api/v1/campaigns/{id}", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("{" + VALID_RULES + "}")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.retryPolicy.rules[0].retryDelay")
                            .value("10:00"));
        }
    }

    @Nested
    class Rejected {

        @ParameterizedTest(name = "RAPI-6: retryDelay \"{0}\" -> 400")
        @ValueSource(strings = {"5", "5:0", "005:00", "1h30m", "abc", "00-30", "1:2:3"})
        @DisplayName("RAPI-6: a malformed or out-of-range MM:SS delay is rejected before the service")
        void malformedDelayRejected(String delay) throws Exception {
            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("{\"maxAttempts\":0,\"rules\":["
                                    + "{\"category\":\"NO_ANSWER\",\"enabled\":true,"
                                    + "\"maxRetries\":1,\"retryDelay\":\"" + delay + "\"}]}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            verify(campaignService, never()).create(any(), any());
        }

        @ParameterizedTest(name = "RAPI-7: maxRetries={0} -> 400")
        @ValueSource(ints = {-1, 11, 100})
        @DisplayName("RAPI-7: a negative or above-maximum retry count is rejected")
        void outOfRangeRetriesRejected(int maxRetries) throws Exception {
            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("{\"maxAttempts\":0,\"rules\":["
                                    + "{\"category\":\"BUSY\",\"enabled\":true,\"maxRetries\":"
                                    + maxRetries + ",\"retryDelay\":\"05:00\"}]}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            verify(campaignService, never()).create(any(), any());
        }

        @Test
        @DisplayName("RAPI-8: a missing category is rejected")
        void missingCategoryRejected() throws Exception {
            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("{\"maxAttempts\":0,\"rules\":["
                                    + "{\"enabled\":true,\"maxRetries\":1,"
                                    + "\"retryDelay\":\"05:00\"}]}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            verify(campaignService, never()).create(any(), any());
        }

        @Test
        @DisplayName("RAPI-9: an unknown category is rejected (not silently defaulted)")
        void unknownCategoryRejected() throws Exception {
            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("{\"maxAttempts\":0,\"rules\":["
                                    + "{\"category\":\"SOMETHING_ELSE\",\"enabled\":true,"
                                    + "\"maxRetries\":1,\"retryDelay\":\"05:00\"}]}")))
                    .andExpect(status().isBadRequest());
            verify(campaignService, never()).create(any(), any());
        }

        @Test
        @DisplayName("RAPI-10: a missing maxRetries is rejected")
        void missingMaxRetriesRejected() throws Exception {
            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("{\"maxAttempts\":0,\"rules\":["
                                    + "{\"category\":\"BUSY\",\"enabled\":true,"
                                    + "\"retryDelay\":\"05:00\"}]}")))
                    .andExpect(status().isBadRequest());
            verify(campaignService, never()).create(any(), any());
        }

        @Test
        @DisplayName("RAPI-11: the reserved categories ARE accepted as configuration")
        void reservedCategoriesAccepted() throws Exception {
            // SWITCHED_OFF / NOT_REACHABLE are supported policy categories for
            // a future reliable provider mapping, so configuring them is legal
            // and must not be rejected.
            stubCreateEchoing();

            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("{\"maxAttempts\":0,\"rules\":["
                                    + "{\"category\":\"SWITCHED_OFF\",\"enabled\":true,"
                                    + "\"maxRetries\":2,\"retryDelay\":\"30:00\"},"
                                    + "{\"category\":\"NOT_REACHABLE\",\"enabled\":true,"
                                    + "\"maxRetries\":1,\"retryDelay\":\"45:00\"}]}")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.retryPolicy.rules[0].category")
                            .value("SWITCHED_OFF"))
                    .andExpect(jsonPath("$.data.retryPolicy.rules[1].category")
                            .value("NOT_REACHABLE"));
        }

        @Test
        @DisplayName("RAPI-12: a syntactically valid but out-of-range delay is a DOMAIN rule, not a DTO rule")
        void rangeDelayIsNotADtoRule() throws Exception {
            // "00:60" and "00:00" match the MM:SS @Pattern perfectly, so the DTO
            // layer cannot reject them. They are caught by
            // RetryPolicyValidator.validateView, which converts the parse
            // failure into the 400 contract. Asserted here so the split is
            // explicit: this slice test proves the pattern boundary only.
            // The range boundary is proven in RetryPolicyValidatorTest and
            // RetryPolicySnapshotPostgresIntegrationTest.
            stubCreateEchoing();

            for (String syntacticallyValidButInvalid : new String[] {"00:60", "00:00", "99:60"}) {
                mockMvc.perform(post("/api/v1/campaigns")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody("{\"maxAttempts\":0,\"rules\":["
                                        + "{\"category\":\"NO_ANSWER\",\"enabled\":true,"
                                        + "\"maxRetries\":1,\"retryDelay\":\""
                                        + syntacticallyValidButInvalid + "\"}]}")))
                        .andExpect(status().isCreated());
            }

            // And the domain validator does reject each of them, with the
            // platform's validation error rather than a 500.
            for (String syntacticallyValidButInvalid : new String[] {"00:60", "00:00", "99:60"}) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                                RetryPolicyValidator.validateView(
                                        new com.shivang.obd.campaign.dto.RetryPolicyConfig(
                                                0, null, RetryStrategy.FIXED,
                                                java.util.List.of(new com.shivang.obd.campaign.dto
                                                        .RetryRuleConfig(
                                                        RetryRuleCategory.NO_ANSWER, Boolean.TRUE,
                                                        1, syntacticallyValidButInvalid)))))
                        .as("delay %s must be a 400-class validation error", syntacticallyValidButInvalid)
                        .isInstanceOf(com.shivang.obd.common.exception.BusinessException.class)
                        .hasMessageContaining("retryDelay");
            }
        }
    }
}
