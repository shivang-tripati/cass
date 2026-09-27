package com.shivang.obd.security.detection;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SuspiciousActivityDetectorTest {

    private SimpleMeterRegistry meterRegistry;
    private SuspiciousActivityDetector detector;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        detector = new SuspiciousActivityDetector(meterRegistry);
    }

    @Test
    void tokenReuseSignalIncrementsTypedCounter() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        detector.process(SecuritySignal.of(
            SecuritySignalType.TOKEN_REUSE, SecuritySignalSeverity.CRITICAL,
            userId, familyId));

        double count = meterRegistry.get("obd.security.suspicious.signals")
            .tag("type", "TOKEN_REUSE")
            .tag("severity", "CRITICAL")
            .counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void excessiveLoginFailureSignalIsCountedSeparatelyBySeverity() {
        detector.process(SecuritySignal.of(
            SecuritySignalType.EXCESSIVE_LOGIN_FAILURES,
            SecuritySignalSeverity.HIGH, null, null));

        var counter = meterRegistry.get("obd.security.suspicious.signals")
            .tag("type", "EXCESSIVE_LOGIN_FAILURES")
            .tag("severity", "HIGH")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);

        detector.process(SecuritySignal.of(
            SecuritySignalType.EXCESSIVE_LOGIN_FAILURES,
            SecuritySignalSeverity.HIGH, null, null));
        assertThat(counter.count()).isEqualTo(2.0);
    }
}
