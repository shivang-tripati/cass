-- =====================================================================
-- Deterministic contact deduplication support (Phase 2J.2)
-- Forward-only; V1-V18 remain untouched.
--
-- Guarantees the group-scoped canonical phone identity at the database
-- level: two concurrent imports cannot both insert the same live number
-- into one group. Live-only (deleted_at IS NULL) preserves the existing
-- soft-delete semantics: a phone becomes reusable once its contact is
-- soft-deleted.
-- =====================================================================

CREATE UNIQUE INDEX uq_contacts_group_phone_live
    ON contacts (contact_group_id, phone_number)
    WHERE deleted_at IS NULL;
