-- =====================================================================
-- VB-6D.3 — Voice Blast campaign daily ATTEMPT safety
--
-- Forward-only; V1-V49 remain untouched.
--
-- WHAT THIS IS NOT: this is NOT the VB-6C provider-accepted dial limit.
-- The two are separate controls with different keys, different counting
-- events, and different purposes, and are deliberately NOT merged:
--
--   V47 voice_blast_daily_usage          "may another provider-ACCEPTED
--       key (tenant, contact, did, day)   dial occur for this contact on
--       counted at ESL +OK                this DNID today?"   [3 max]
--
--   V50 voice_blast_daily_attempts       "may this contact be
--       key (tenant, contact, day)        DISPATCHED to again
--       counted at dial issuance          today, across ALL Voice Blast
--                                         campaigns?"         [10 max]
--
-- WHY A SEPARATE TABLE. VB-6C is DNID-scoped and counts connections the
-- subscriber actually received. This control is DNID-agnostic and counts
-- every dial issued to a contact, including ones the provider rejected.
-- The bucket keys differ (no did_id), the counting event differs (+OK vs
-- dial issuance), and the ceilings differ (3 vs 10) — so one table cannot
-- express both without either losing the DNID scope or conflating
-- "connection made" with "ring attempted". See docs/VB-6D.3-IMPLEMENTATION
-- -REPORT.md for the full comparison.
--
-- WHY ONE COUNTER, NOT RESERVE/CONFIRM. VB-6C needs a hold because it
-- counts at provider acceptance, an event that happens after the
-- transaction that admitted the dial. This control counts at DIAL
-- ISSUANCE, which happens inside the admitting transaction, so the
-- increment IS the consumption: there is nothing to confirm later and
-- therefore no reservation that a crash could strand. Deliberately
-- simpler than V47, not more complex.
--
-- CONCURRENCY: the same proven single-statement conditional-UPDATE
-- pattern as V47 — the row lock on the unique bucket row is the
-- concurrency authority, so admitted <= limit under any number of
-- concurrent workers, with no advisory lock, no SKIP LOCKED, and no
-- distributed lock.
--
-- Backward compatibility: new table, no backfill, nothing to migrate. An
-- existing deployment starts counting from zero and is thereby at most as
-- restrictive as the moment the feature is deployed.
-- =====================================================================

CREATE TABLE voice_blast_daily_attempts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Tenant is the isolation boundary and is part of the bucket key, so a
    -- cross-tenant key can never collide (same rationale as V47).
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    contact_id  UUID NOT NULL,

    -- Calendar day in the EXECUTION SNAPSHOT's IANA timezone, resolved by
    -- the same DailyDialLimitService.resolveUsageDate(...) seam that
    -- already defines "today" for VB-6C. One day-boundary authority for
    -- both controls, deliberately.
    usage_date  DATE NOT NULL,

    -- Dials issued to this contact today. Non-negative by construction; a
    -- decrement is never performed, which is what makes a stranded value
    -- impossible here (unlike a hold-based counter).
    attempt_count INTEGER NOT NULL DEFAULT 0
                 CONSTRAINT ck_vbda_attempt_non_negative CHECK (attempt_count >= 0),

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  VARCHAR(255),
    updated_at  TIMESTAMPTZ,
    updated_by  VARCHAR(255),

    -- No did_id: the whole point is a contact-day ceiling shared across
    -- every Voice Blast campaign of the tenant, so rotating the DNID
    -- cannot reset it.
    CONSTRAINT uq_vbda_bucket UNIQUE (tenant_id, contact_id, usage_date)
);

CREATE INDEX idx_vbda_contact_date ON voice_blast_daily_attempts (contact_id, usage_date);
