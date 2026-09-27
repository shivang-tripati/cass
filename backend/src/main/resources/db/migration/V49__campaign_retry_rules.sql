-- =====================================================================
-- VB-6D.2 — campaign retry rule model (per-category retry policy)
--
-- Forward-only; V1-V48 remain untouched.
--
-- The campaign retry policy gains an optional set of per-category rules
-- (NO_ANSWER / BUSY / HANGUP / FAILED / SWITCHED_OFF / NOT_REACHABLE),
-- each carrying: enabled, maxRetries and an MM:SS retryDelay.
--
-- Storage decision — JSONB, not a relational child table:
--
--   * the rule set is a small bounded group (at most one rule per category);
--   * it is always read and written as a single unit with the campaign
--     configuration, never queried relationally (no "which campaigns retry
--     BUSY" report is a requirement, and none exists today);
--   * it must be frozen VERBATIM into the immutable execution snapshot, which
--     makes it one aggregate value rather than a set of independently
--     addressable rows.
--
-- A relational model would add a second persistence shape, a join on every
-- campaign read, and a second thing to keep in sync at execution creation —
-- to express a value that is inherently one unit. The type is strictly
-- validated in the domain layer (RetryPolicySpec / RetryRule) and at the API
-- boundary (duplicate categories are rejected there, which is why the set is
-- a JSON array rather than an object: a map would silently drop a duplicate
-- instead of reporting it). The CHECK below asserts only that the column is
-- either absent or a JSON array — the strongest structural guarantee that
-- belongs in the schema without duplicating the domain rules as SQL.
--
-- The same nullable column is added to campaign_execution_configurations:
-- the VB-6A immutable snapshot freezes the campaign's configured rules at
-- execution-creation time. An execution therefore never changes retry
-- behaviour because the campaign was edited later. There is NO versioning,
-- no MAX(version), no history table, and no fallback read.
--
-- Backward compatibility: existing campaigns keep NULL, which means "no
-- per-category rules configured" — the pre-existing flat policy
-- (retry_max_attempts / retry_interval_seconds / retry_strategy) governs
-- exactly as before. No backfill, no behaviour change.
-- =====================================================================

ALTER TABLE campaigns
    ADD COLUMN retry_rules JSONB
    CONSTRAINT ck_campaigns_retry_rules_array
        CHECK (retry_rules IS NULL OR jsonb_typeof(retry_rules) = 'array');

ALTER TABLE campaign_execution_configurations
    ADD COLUMN retry_rules JSONB
    CONSTRAINT ck_cec_retry_rules_array
        CHECK (retry_rules IS NULL OR jsonb_typeof(retry_rules) = 'array');
