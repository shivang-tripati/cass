package com.shivang.obd.campaign;

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
 * VB-6D.3 — REST contract for the campaign daily-attempt ceiling.
 *
 * <p>Proves the API boundary: a valid ceiling is accepted and echoed
 * verbatim, an out-of-range one is rejected with the platform's existing 400
 * {@code VALIDATION_ERROR} ProblemDetail <em>before</em> the service is
 * reached, and an omitted value is reported as absent rather than being
 * silently normalized to the platform default — so a client can still tell
 * "not configured" from "configured to 10".
 */
class CampaignDailyAttemptApiSliceTest {

    private static final UUID CAMPAIGN_ID =
            UUID.fromString("e0e0e000-0000-4000-8000-000000000001");
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

    private CampaignResponse responseWith(Integer maxDailyAttempts) {
        return new CampaignResponse(
                CAMPAIGN_ID, TENANT_A, "Attempt ceiling", null,
                CampaignType.PLAYFILE, CampaignRunMode.ONE_TIME, CampaignStatus.DRAFT,
                null, null, null, null, ContentMode.AUDIO, null, null,
                null, null, null, null, null, null, false, null, maxDailyAttempts);
    }

    private void stubCreateEchoing() {
        when(campaignService.create(any(CreateCampaignRequest.class), isNull()))
                .thenAnswer(inv -> ResponseFactory.created(responseWith(
                        inv.<CreateCampaignRequest>getArgument(0).maxDailyAttempts())));
    }

    private void stubUpdateEchoing() {
        when(campaignService.update(eq(CAMPAIGN_ID), any(UpdateCampaignRequest.class)))
                .thenAnswer(inv -> ResponseFactory.ok(responseWith(
                        inv.<UpdateCampaignRequest>getArgument(1).maxDailyAttempts())));
    }

    private String createBody(String field) {
        return field.isEmpty()
                ? "{\"name\":\"Attempt ceiling\",\"campaignType\":\"PLAYFILE\"}"
                : "{\"name\":\"Attempt ceiling\",\"campaignType\":\"PLAYFILE\"," + field + "}";
    }

    private String updateBody(String field) {
        return "{\"name\":\"Attempt ceiling\"," + field + "}";
    }

    @Nested
    class Accepted {

        @ParameterizedTest(name = "EAPI-1: maxDailyAttempts={0} accepted and echoed")
        @ValueSource(ints = {1, 2, 5, 10})
        @DisplayName("EAPI-1: every valid ceiling is accepted and echoed verbatim")
        void validCeilingsAccepted(int value) throws Exception {
            stubCreateEchoing();

            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("\"maxDailyAttempts\":" + value)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.maxDailyAttempts").value(value));

            ArgumentCaptor<CreateCampaignRequest> captor =
                    ArgumentCaptor.forClass(CreateCampaignRequest.class);
            verify(campaignService).create(captor.capture(), isNull());
            org.assertj.core.api.Assertions
                    .assertThat(captor.getValue().maxDailyAttempts()).isEqualTo(value);
        }

        @Test
        @DisplayName("EAPI-2: an omitted ceiling stays absent (platform default, not normalized)")
        void omittedStaysAbsent() throws Exception {
            stubCreateEchoing();

            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.maxDailyAttempts").doesNotExist());

            ArgumentCaptor<CreateCampaignRequest> captor =
                    ArgumentCaptor.forClass(CreateCampaignRequest.class);
            verify(campaignService).create(captor.capture(), isNull());
            org.assertj.core.api.Assertions
                    .assertThat(captor.getValue().maxDailyAttempts())
                    .as("null means platform default and must be distinguishable from 10")
                    .isNull();
        }

        @Test
        @DisplayName("EAPI-3: UPDATE accepts and echoes the ceiling")
        void updateAcceptsCeiling() throws Exception {
            stubUpdateEchoing();

            mockMvc.perform(put("/api/v1/campaigns/{id}", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("\"maxDailyAttempts\":4")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.maxDailyAttempts").value(4));
        }

        @Test
        @DisplayName("EAPI-4: the two daily limits are independent fields on one request")
        void bothLimitsCoexist() throws Exception {
            stubCreateEchoing();

            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("\"dailyDialLimit\":2,\"maxDailyAttempts\":6")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.maxDailyAttempts").value(6));

            ArgumentCaptor<CreateCampaignRequest> captor =
                    ArgumentCaptor.forClass(CreateCampaignRequest.class);
            verify(campaignService).create(captor.capture(), isNull());
            org.assertj.core.api.Assertions
                    .assertThat(captor.getValue().dailyDialLimit()).isEqualTo(2);
            org.assertj.core.api.Assertions
                    .assertThat(captor.getValue().maxDailyAttempts()).isEqualTo(6);
        }
    }

    @Nested
    class Rejected {

        @ParameterizedTest(name = "EAPI-5: maxDailyAttempts={0} -> 400")
        @ValueSource(ints = {0, -1, 11, 100})
        @DisplayName("EAPI-5: 0, negative and above-platform-maximum are rejected before the service")
        void outOfRangeRejected(int value) throws Exception {
            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("\"maxDailyAttempts\":" + value)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'maxDailyAttempts')]").exists());
            verify(campaignService, never()).create(any(), any());
        }

        @Test
        @DisplayName("EAPI-6: UPDATE rejects an above-maximum ceiling too")
        void updateRejectsOutOfRange() throws Exception {
            mockMvc.perform(put("/api/v1/campaigns/{id}", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("\"maxDailyAttempts\":50")))
                    .andExpect(status().isBadRequest());
            verify(campaignService, never()).update(any(), any());
        }
    }
}
