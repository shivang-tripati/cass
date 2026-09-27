package com.shivang.obd.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import java.time.Instant;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.security.detection.SecuritySignal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
class SecurityResponseServiceTest {

    private final RefreshTokenRevocationService revocationService =
        mock(RefreshTokenRevocationService.class);
    private final RefreshTokenFamilyRevoker compromisedFamilyRevoker =
        mock(RefreshTokenFamilyRevoker.class);
    private final SecurityResponseService service =
        new SecurityResponseService(revocationService, compromisedFamilyRevoker);

    @Test
    void revokeFamilyIsOwnershipAwareAndReasonStamped() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        when(revocationService.revokeFamilyForUser(userId, familyId,
            RevocationReason.LOGOUT)).thenReturn(1);

        int affected = service.revokeFamily(userId, familyId, RevocationReason.LOGOUT);

        assertThat(affected).isEqualTo(1);
        verify(revocationService).revokeFamilyForUser(userId, familyId, RevocationReason.LOGOUT);
    }

    @Test
    void revokeAllUserSessionsDelegatesWithReason() {
        UUID userId = UUID.randomUUID();
        when(revocationService.revokeAllUserSessions(userId, RevocationReason.SECURITY_RESPONSE))
            .thenReturn(3);

        int affected = service.revokeAllUserSessions(userId, RevocationReason.SECURITY_RESPONSE);

        assertThat(affected).isEqualTo(3);
    }

    @Test
    void compromisedFamilyGoesThroughIndependentTransactionPath() {
        UUID familyId = UUID.randomUUID();

        service.revokeCompromisedFamily(familyId);

        verify(compromisedFamilyRevoker).revokeCompromisedFamily(familyId);
        // Guard: the REQUIRES_NEW contract must not be weakened.
        var invocations = new java.util.ArrayList<>(
            org.mockito.Mockito.mockingDetails(compromisedFamilyRevoker).getInvocations());
        var method = invocations.get(0).getMethod();
        var requiresNew = method.getAnnotation(Transactional.class);
        assertThat(requiresNew.propagation())
            .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void securityResponseReasonExistsForSuspiciousActivityFlows() {
        assertThat(RevocationReason.valueOf("SECURITY_RESPONSE"))
            .isNotNull();
    }
}
