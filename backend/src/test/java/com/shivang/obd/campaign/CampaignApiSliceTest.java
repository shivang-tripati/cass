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
 * VB-6C.2 API contract test for the campaign {@code dailyDialLimit} field
 * over real HTTP (standalone-MockMvc pattern, same as
 * {@code ContactGroupMemberApiSliceTest}).
 * <p>
 * Covers the API boundary the DTO-validation suite cannot: that a valid
 * configured value is deserialized and handed to the service unchanged, that
 * an out-of-range value is rejected with the platform's existing 400
 * {@code VALIDATION_ERROR} ProblemDetail <em>before</em> the service is
 * reached, and that the response echoes the CONFIGURED value — including
 * {@code null} as "not explicitly configured", never silently normalized to
 * the platform maximum of 3.
 * <p>
 * The service-level business rule and capability checks are covered by
 * {@code CampaignDailyDialLimitServiceTest}; generated schema shapes by
 * {@code CampaignOpenApiContractTest}. Full security wiring (401/403) is
 * covered centrally by {@code SecuritySliceTest}.
 */
class CampaignApiSliceTest {

    private static final UUID CAMPAIGN_ID =
            UUID.fromString("c0ffee00-0000-4000-8000-000000000001");
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
                .setControllerAdvice(new com.shivang.obd.common.exception
                        .GlobalExceptionHandler())
                .build();
    }

    /** Echoes back whatever was accepted, so the response value is genuine. */
    private void stubCreateEchoingRequestedLimit() {
        when(campaignService.create(any(CreateCampaignRequest.class), isNull()))
                .thenAnswer(invocation -> {
                    CreateCampaignRequest request = invocation.getArgument(0);
                    return ResponseFactory.created(responseFor(request.dailyDialLimit()));
                });
    }

    private void stubUpdateEchoingRequestedLimit() {
        when(campaignService.update(eq(CAMPAIGN_ID), any(UpdateCampaignRequest.class)))
                .thenAnswer(invocation -> {
                    UpdateCampaignRequest request = invocation.getArgument(1);
                    return ResponseFactory.ok(responseFor(request.dailyDialLimit()));
                });
    }

    private CampaignResponse responseFor(Integer configured) {
        return new CampaignResponse(
                CAMPAIGN_ID, TENANT_A, "Voice blast", null,
                CampaignType.PLAYFILE, CampaignRunMode.ONE_TIME,
                CampaignStatus.DRAFT, null, null,
                null, null, ContentMode.AUDIO, null, null,
                null, null, null, null, null, null, false,
                configured);
    }

    private String createBody(String dailyDialLimitJson) {
        return "{\"name\":\"Voice blast\",\"campaignType\":\"PLAYFILE\""
                + dailyDialLimitJson + "}";
    }

    private String updateBody(String dailyDialLimitJson) {
        return "{\"name\":\"Voice blast\"" + dailyDialLimitJson + "}";
    }

    @Nested
    class Create {

        @Test
        @DisplayName("API-C1: POST without dailyDialLimit → 201, configured value stays null")
        void omittedIsAcceptedAsNull() throws Exception {
            stubCreateEchoingRequestedLimit();

            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    // null must survive the round trip: "not configured" is
                    // distinguishable from "configured 3".
                    .andExpect(jsonPath("$.data.dailyDialLimit").doesNotExist());

            ArgumentCaptor<CreateCampaignRequest> captor =
                    ArgumentCaptor.forClass(CreateCampaignRequest.class);
            verify(campaignService).create(captor.capture(), isNull());
            assertThat(captor.getValue().dailyDialLimit()).isNull();
        }

        @ParameterizedTest(name = "API-C2: POST dailyDialLimit={0} → 201 and echoed")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("API-C2: POST with 1, 2, 3 → 201 and the value is echoed")
        void validValuesAreAcceptedAndEchoed(int value) throws Exception {
            stubCreateEchoingRequestedLimit();

            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(",\"dailyDialLimit\":" + value)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.dailyDialLimit").value(value));

            ArgumentCaptor<CreateCampaignRequest> captor =
                    ArgumentCaptor.forClass(CreateCampaignRequest.class);
            verify(campaignService).create(captor.capture(), isNull());
            assertThat(captor.getValue().dailyDialLimit()).isEqualTo(value);
        }

        @ParameterizedTest(name = "API-C3: POST dailyDialLimit={0} → 400")
        @ValueSource(ints = {0, -1, 4, 100})
        @DisplayName("API-C3: POST with 0, -1, 4, 100 → 400, service never reached")
        void invalidValuesRejectedBeforeTheService(int value) throws Exception {
            mockMvc.perform(post("/api/v1/campaigns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(",\"dailyDialLimit\":" + value)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'dailyDialLimit')]").exists());

            verify(campaignService, never()).create(any(), any());
        }
    }

    @Nested
    class Update {

        @Test
        @DisplayName("API-U1: PUT without dailyDialLimit → 200, configured value stays null")
        void omittedIsAcceptedAsNull() throws Exception {
            stubUpdateEchoingRequestedLimit();

            mockMvc.perform(put("/api/v1/campaigns/{id}", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.dailyDialLimit").doesNotExist());

            ArgumentCaptor<UpdateCampaignRequest> captor =
                    ArgumentCaptor.forClass(UpdateCampaignRequest.class);
            verify(campaignService).update(eq(CAMPAIGN_ID), captor.capture());
            assertThat(captor.getValue().dailyDialLimit()).isNull();
        }

        @ParameterizedTest(name = "API-U2: PUT dailyDialLimit={0} → 200 and echoed")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("API-U2: PUT with 1, 2, 3 → 200 and the value is echoed")
        void validValuesAreAcceptedAndEchoed(int value) throws Exception {
            stubUpdateEchoingRequestedLimit();

            mockMvc.perform(put("/api/v1/campaigns/{id}", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody(",\"dailyDialLimit\":" + value)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.dailyDialLimit").value(value));

            ArgumentCaptor<UpdateCampaignRequest> captor =
                    ArgumentCaptor.forClass(UpdateCampaignRequest.class);
            verify(campaignService).update(eq(CAMPAIGN_ID), captor.capture());
            assertThat(captor.getValue().dailyDialLimit()).isEqualTo(value);
        }

        @ParameterizedTest(name = "API-U3: PUT dailyDialLimit={0} → 400")
        @ValueSource(ints = {0, -1, 4, 100})
        @DisplayName("API-U3: PUT with 0, -1, 4, 100 → 400, service never reached")
        void invalidValuesRejectedBeforeTheService(int value) throws Exception {
            mockMvc.perform(put("/api/v1/campaigns/{id}", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody(",\"dailyDialLimit\":" + value)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'dailyDialLimit')]").exists());

            verify(campaignService, never()).update(any(), any());
        }
    }
}
