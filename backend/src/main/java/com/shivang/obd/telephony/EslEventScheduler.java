package com.shivang.obd.telephony;

import com.shivang.obd.campaign.EslEventProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler for FreeSWITCH ESL event processing.
 * <p>
 * Runs the ESL event subscription loop in a background thread.
 * The event loop is blocking, so it runs in a dedicated executor.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EslEventScheduler implements EslEventProcessor {

    private final FreeSwitchProperties properties;
    private final EslEventService eventService;

    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "freeswitch-esl-event-processor");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Starts the ESL event processing loop.
     * Runs every 60 seconds to ensure connection is alive and restart if needed.
     */
    @Scheduled(fixedDelay = 60000)
    public void ensureEventProcessing() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!running.get()) {
            startEventProcessing();
        }
    }

    private void startEventProcessing() {
        if (!running.compareAndSet(false, true)) {
            return; // Already running
        }

        eventExecutor.submit(() -> {
            try {
                log.info("Starting FreeSWITCH ESL event processing loop");
                EslClient eslClient = new EslClient(properties);
                eslClient.connect();
                eslClient.subscribeAndProcessEvents(this::processEventSafely);
            } catch (Exception e) {
                log.error("ESL event processing loop terminated unexpectedly", e);
            } finally {
                running.set(false);
                log.warn("ESL event processing loop stopped, will restart on next schedule");
            }
        });
    }

    private void processEventSafely(EslEvent event) {
        try {
            eventService.processEvent(event);
        } catch (Exception e) {
            log.error("Error processing ESL event: {}", event.getEventName(), e);
        }
    }

    /**
     * Shutdown hook to gracefully stop event processing.
     */
    public void shutdown() {
        log.info("Shutting down ESL event scheduler");
        eventExecutor.shutdown();
        try {
            if (!eventExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                eventExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            eventExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}