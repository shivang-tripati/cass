-- =====================================================================
-- Voice Blast daily dial-limit ledger (VB-6C.1)
-- Forward-only; V1-V46 remain untouched. No backfill: the policy is
-- prospective — every day bucket starts empty.
--
-- Two relational structures on purpose (audit §8 requires BOTH
-- guarantees and neither structure can carry the other):
--
--   1. voice_blast_daily_usage          — the ATOMIC BUCKET COUNTER.
--      Identity: (tenant_id, contact_id, did_id, usage_date).
--      Unique key + conditional UPDATE is the no-over-admission
--      authority: concurrent workers can never push usage past the
--      effective limit, because admission/confirmation is a
--      single-statement guarded increment on this row.
--
--   2. voice_blast_daily_usage_entries  — PHYSICAL PER-ATTEMPT LEDGER.
--      UNIQUE (call_attempt_id) makes double-counting one CallAttempt
--      (replayed acceptance handling, duplicate +OK processing)
--      physically impossible at the database boundary, independent of
--      any application guard.
--
-- Bucket-key conventions follow the established campaign patterns:
-- tenant_id is part of the key (tenant isolation, §21) with a real FK
-- to tenants; contact_id / did_id / call_attempt_id are plain UUID
-- references exactly like call_attempts (V22) — composite FKs would
-- require new UNIQUE (id, tenant_id) keys on contacts/dids/call_attempts,
-- which this phase must not modify. Usage rows are physical: they are
-- accounting facts, not lifecycle entities, so there are deliberately
-- NO soft-delete columns (same rationale as contact_group_members,
-- V46).
--
-- The recorded DID is the ACTUAL OUTBOUND DNID selected by routing
-- (VoiceRoute.didId) — the DNID the provider sees — not the campaign's
-- requested DID when a route profile pinned a different one (audit §5).
-- =====================================================================

CREATE TABLE voice_blast_daily_usage (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants (id),
    contact_id    UUID NOT NULL,
    did_id        UUID NOT NULL,
    usage_date    DATE NOT NULL,

    -- Slots this bucket has granted (admission stage). Every grant is
    -- either confirmed (+OK → used) or explicitly released (dial did
    -- not reach the provider), so this is a short-lived hold count.
    reserved_count INT NOT NULL DEFAULT 0
                   CONSTRAINT ck_vbdu_reserved_non_negative CHECK (reserved_count >= 0),

    -- Provider-ACCEPTED dials (+OK <uuid>) for this bucket. THE policy
    -- figure: a dial counts here only after the provider accepted it;
    -- ringing/busy/no-answer/failure AFTER acceptance still counts.
    used_count     INT NOT NULL DEFAULT 0
                   CONSTRAINT ck_vbdu_used_non_negative CHECK (used_count >= 0),

    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(255),

    CONSTRAINT uq_vbdu_bucket UNIQUE (tenant_id, contact_id, did_id, usage_date)
);

CREATE INDEX idx_vbdu_contact_date ON voice_blast_daily_usage (contact_id, usage_date);

-- One usage row per provider-accepted attempt — ever. The database, not
-- application discipline, guarantees "counted exactly once".
CREATE TABLE voice_blast_daily_usage_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    call_attempt_id UUID NOT NULL,
    contact_id      UUID NOT NULL,
    did_id          UUID NOT NULL,
    usage_date      DATE NOT NULL,
    provider_call_id VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),

    CONSTRAINT uq_vbdue_call_attempt UNIQUE (call_attempt_id)
);

CREATE INDEX idx_vbdue_bucket
    ON voice_blast_daily_usage_entries (tenant_id, contact_id, did_id, usage_date);
CREATE INDEX idx_vbdue_tenant ON voice_blast_daily_usage_entries (tenant_id);
