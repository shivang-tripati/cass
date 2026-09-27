-- =====================================================================
-- Session/token lifecycle extension (Phase 2B.2)
-- family_id is promoted to the stable SESSION identifier; individual
-- refresh-token rows are the rotating credentials of that session.
-- Adds lifecycle observability: why a token was revoked.
-- Forward-only; V1/V2/V3 remain untouched.
-- =====================================================================

ALTER TABLE refresh_tokens
    ADD COLUMN revocation_reason VARCHAR(40);

-- Index review: user_id, family_id and token_hash access paths are already
-- indexed by V3 (idx_refresh_tokens_user, idx_refresh_tokens_family,
-- unique token_hash). Logout-all scans by user_id -> existing index suffices.
-- No speculative indexes added.
