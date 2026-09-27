-- =====================================================================
-- VB-6E — maximum call duration + per-call deadline
--
-- Forward-only; V1-V51 remain untouched.
--
-- WHAT THIS IS NOT: this is not the VB-6C provider-accepted dial limit and
-- not the VB-6D.3 campaign attempt ceiling. Those count dials and dispatches.
-- This bounds the LIFETIME OF AN ESTABLISHED CALL SESSION, which is a
-- different question with a different key. See
-- docs/VB-6E-PLAYFILE-COMMON-CALLING-IMPLEMENTATION.md.
--
-- (1) max_call_duration_seconds on campaigns and
--     campaign_execution_configurations
--
--   Maximum lifetime of an established outbound call, in seconds.
--   NULL means "use the platform default" (300s), matching the existing
--   VB-6C.2 / VB-6D.3 convention where null = platform default and a campaign
--   may only NARROW the ceiling. The bound is duplicated from the code
--   constant exactly as V48 and V51 duplicated theirs: the database is the
--   last line of defense and must refuse to store a value the application
--   could not honour. The code constant remains the single authority for the
--   effective value.
--
--   The SAME nullable column is added to campaign_execution_configurations:
--   the VB-6A immutable snapshot freezes it at execution-creation time, so
--   editing a campaign later can neither lengthen nor shorten an execution
--   that already exists. No versioning, no history table, no fallback read.
--
--   NOTE the semantic is the ACTIVE SESSION lifetime, not a ring timeout and
--   not a playback length. The deadline is computed once, when the call is
--   answered, from the session's own answered_at.
--
-- (2) deadline_at on call_sessions
--
--   The per-call, authoritative expiry instant, computed at answer time as
--   answered_at + frozen max duration. Persisting it (rather than recomputing
--   on every sweep) is what makes the sweeper a single indexed range scan and
--   makes the timeout idempotent: a session past its deadline is terminal for
--   timeout purposes no matter how many times it is examined, and a duplicate
--   or late ESL event cannot move the deadline.
--
--   NULL for sessions that were never answered, for non-Voice-Blast call
--   types, and for any session created before this migration: those are not
--   governed by this control and are never swept.
--
-- Backward compatibility: no backfill. Existing campaigns keep NULL, which
-- means the 300s platform default, so no existing campaign changes behaviour.
-- Existing call sessions get NULL deadline and are never swept.
-- =====================================================================

ALTER TABLE campaigns
    ADD COLUMN max_call_duration_seconds SMALLINT
    CONSTRAINT ck_campaigns_max_call_duration
        CHECK (max_call_duration_seconds IS NULL
               OR max_call_duration_seconds BETWEEN 1 AND 3600);

ALTER TABLE campaign_execution_configurations
    ADD COLUMN max_call_duration_seconds SMALLINT
    CONSTRAINT ck_cec_max_call_duration
        CHECK (max_call_duration_seconds IS NULL
               OR max_call_duration_seconds BETWEEN 1 AND 3600);

ALTER TABLE call_sessions
    ADD COLUMN deadline_at TIMESTAMPTZ
    CONSTRAINT ck_call_sessions_deadline_after_answered
        CHECK (deadline_at IS NULL OR answered_at IS NULL OR deadline_at >= answered_at);

-- The sweeper's access path: "active sessions whose deadline has passed".
-- Partial so the index only carries live rows, and tenant-scoped first
-- because every sweeper query is tenant-bounded.
CREATE INDEX idx_call_sessions_deadline
    ON call_sessions (deadline_at)
    WHERE deleted_at IS NULL AND deadline_at IS NOT NULL;
