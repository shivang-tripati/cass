package com.shivang.obd.security.detection;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Central observer for suspicious-activity signals. Signals are produced by
 * real security flows (token reuse, rate-limit blocking); this component
 * makes them observable (metrics + safe logs). Reactive responses (family
 * revocation) remain the explicit responsibility of SecurityResponseService
 * invoked by the producing flow — never performed implicitly here.
 */
@Component
public class SuspiciousActivityDetector {

    private static final Logger log = LoggerFactory.getLogger(SuspiciousActivityDetector.class);

    private final MeterRegistry meterRegistry;

    public SuspiciousActivityDetector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void process(SecuritySignal signal) {
        io.micrometer.core.instrument.Counter.builder("obd.security.suspicious.signals")
            .tag("type", signal.type().name())
            .tag("severity", signal.severity().name())
            .register(meterRegistry)
            .increment();
        if (log.isInfoEnabled()) {
            log.info("Security signal [type={} severity={} userId={} familyId={}]",
                signal.type(), signal.severity(), signal.userId(), signal.familyId());
        }
    }
}
