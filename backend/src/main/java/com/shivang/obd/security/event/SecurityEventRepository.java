package com.shivang.obd.security.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SecurityEventRepository extends JpaRepository<SecurityEventEntity, UUID> {

    /**
     * Retention cleanup: batch-deletes events older than the cutoff. The
     * occurred_at index (V5) bounds each batch scan. Returns deleted count.
     */
    @Modifying
    @Query(value = """
        DELETE FROM security_events
        WHERE id IN (
            SELECT id FROM security_events
            WHERE occurred_at < :cutoff
            ORDER BY occurred_at
            LIMIT :batchSize
        )
        """, nativeQuery = true)
    int deleteOlderThanBatch(
        @Param("cutoff") Instant cutoff,
        @Param("batchSize") int batchSize);
}
