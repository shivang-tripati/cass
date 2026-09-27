-- =====================================================================
-- Fix campaign_executions audit columns (Phase 2N patch)
-- V21 created the table but the applied DB instance missed audit fields
-- (created_at/created_by/updated_at/updated_by) that AuditableEntity
-- maps. Add them idempotently; also ensures Flyway checksum stays stable
-- for future environments by patching forward.
-- =====================================================================

ALTER TABLE campaign_executions
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Backfill: V21 rows have NULL created_at before NOT NULL; default handles new rows,
-- but existing rows need explicit value where default didn't populate (IF NOT EXISTS path).
UPDATE campaign_executions SET created_at = COALESCE(created_at, requested_at, now())
WHERE created_at IS NULL;

-- Ensure call_attempts also has expected audit if future engine expects it;
-- current CallAttempt is not Auditable but keep consistent with domain pattern.
-- No-op if already present: safe guard for idempotency.
-- (call_attempts currently lacks created_at; future audit would need it.
--  Left untouched here; add only if entity becomes Auditable.)

-- Spring Modulith event publication table (JPA). Required when
-- spring.modulith.events.jpa is on classpath and publication is enabled.
-- Auto-created if spring.modulith.events.jdbc-schema-initialization.enabled=true,
-- but provide Flyway fallback so fresh DBs without that flag still bootstrap.
CREATE TABLE IF NOT EXISTS event_publication (
    id                         UUID PRIMARY KEY,
    completion_attempts        INTEGER,
    completion_date            TIMESTAMPTZ,
    event_type                 VARCHAR(255) NOT NULL,
    last_resubmission_date     TIMESTAMPTZ,
    listener_id                VARCHAR(255),
    publication_date           TIMESTAMPTZ NOT NULL,
    serialized_event           TEXT,
    status                     VARCHAR(20) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_event_publication_status ON event_publication (status);
CREATE INDEX IF NOT EXISTS idx_event_publication_publication_date ON event_publication (publication_date);
