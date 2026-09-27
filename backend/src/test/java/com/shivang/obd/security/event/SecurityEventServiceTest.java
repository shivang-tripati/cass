package com.shivang.obd.security.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SecurityEventServiceTest {

    private SecurityEventRepository repository;
    private SecurityEventRequestContextProvider requestContext;
    private SecurityEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(SecurityEventRepository.class);
        requestContext = mock(SecurityEventRequestContextProvider.class);
        when(requestContext.current()).thenReturn(Optional.of(
            new SecurityEventRequestContextProvider.RequestMetadata(
                "req-abc-123", "127.0.0.1", "TestAgent/1.0")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new SecurityEventService(repository, requestContext);
    }

    private SecurityEventEntity capturedEvent() {
        ArgumentCaptor<SecurityEventEntity> captor = ArgumentCaptor.forClass(SecurityEventEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void loginSuccessIsRepresented() {
        UUID userId = UUID.randomUUID();
        service.recordLoginSuccess(userId);

        SecurityEventEntity event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo(SecurityEventType.LOGIN_SUCCESS);
        assertThat(event.isSuccess()).isTrue();
        assertThat(event.getUserId()).isEqualTo(userId);
    }

    @Test
    void loginFailureIsRepresentedAsUnsuccessful() {
        service.recordLoginFailure(UUID.randomUUID());

        SecurityEventEntity event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo(SecurityEventType.LOGIN_FAILURE);
        assertThat(event.isSuccess()).isFalse();
    }

    @Test
    void unknownUserLoginFailureRecordsNullableUserId() {
        service.recordLoginFailure(null);

        SecurityEventEntity event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo(SecurityEventType.LOGIN_FAILURE);
        assertThat(event.getUserId()).isNull();
        assertThat(event.isSuccess()).isFalse();
    }

    @Test
    void requestIdIpAndUserAgentComeFromServerContext() {
        service.recordTokenRefresh(UUID.randomUUID());

        SecurityEventEntity event = capturedEvent();
        assertThat(event.getRequestId()).isEqualTo("req-abc-123");
        assertThat(event.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(event.getUserAgent()).isEqualTo("TestAgent/1.0");
    }

    @Test
    void eventTimestampIsServerGenerated() {
        service.recordLogout(UUID.randomUUID());

        var event = capturedEvent();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getOccurredAt()).isAfterOrEqualTo(
            java.time.Instant.now().minusSeconds(10));
    }

    @Test
    void tokenReuseDetectedIsRepresentedAsFailure() {
        service.recordTokenReuseDetected(UUID.randomUUID());

        SecurityEventEntity event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo(SecurityEventType.TOKEN_REUSE_DETECTED);
        assertThat(event.isSuccess()).isFalse();
    }

    @Test
    void passwordChangedIsRepresentedAsSuccess() {
        service.recordPasswordChanged(UUID.randomUUID());

        SecurityEventEntity event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo(SecurityEventType.PASSWORD_CHANGED);
        assertThat(event.isSuccess()).isTrue();
    }

    @Test
    void sensitiveMetadataKeysAreDropped() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("password", "SuperSecret!");
        metadata.put("refreshToken", "raw-token-value");
        metadata.put("tokenHash", "deadbeef");
        metadata.put("Authorization", "Bearer eyJhbGciOi...");
        metadata.put("attemptCount", "2");

        var sanitized = SecurityEventService.sanitize(metadata);

        assertThat(sanitized).containsOnlyKeys("attemptCount");
        assertThat(sanitized.get("attemptCount")).isEqualTo("2");
    }

    @Test
    void metadataIsBoundedInCountAndLength() {
        Map<String, String> metadata = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            metadata.put("key" + i, "v".repeat(300));
        }

        var sanitized = SecurityEventService.sanitize(metadata);

        assertThat(sanitized).hasSize(SecurityEventService.MAX_METADATA_ENTRIES);
        assertThat(sanitized.values()).allSatisfy(
            value -> assertThat(value.length())
                .isLessThanOrEqualTo(SecurityEventService.MAX_VALUE_LENGTH));
    }

    @Test
    void persistenceDelegatesToRepository() {
        service.recordLogoutAll(UUID.randomUUID());
        verify(repository).save(any(SecurityEventEntity.class));
    }
}
