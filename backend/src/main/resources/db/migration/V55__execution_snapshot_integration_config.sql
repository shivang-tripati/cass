-- VB-7C.3: freeze campaign integration configuration into the execution snapshot.
--
-- Why a migration is genuinely required
-- --------------------------------------
-- campaign_execution_configurations has exactly two JSONB columns today and BOTH
-- are already in use by the embeddable: allowed_days_of_week and type_config.
-- There is no spare payload column to extend.
--
-- Piggybacking the integration configuration on type_config was considered and
-- rejected. type_config is campaign-TYPE-specific by contract: every
-- CampaignTypeConfig implementation parses exactly one root key and rejects
-- unknown fields, which is what makes the type-isolation guarantee VB-7B added
-- (PLAYFILE cannot carry a missedCall payload) hold. Mixing a type-independent
-- integration payload into it would either break that parsing or make PLAYFILE
-- reject every campaign that has a webhook - both unacceptable.
--
-- So this follows the established precedent exactly. Every previous snapshot
-- field added since V44 was one additive, nullable column in its own migration:
--   V49  retry_rules                JSONB
--   V51  max_daily_attempts         INTEGER
--   V52  max_call_duration_seconds  INTEGER
-- V55 continues that pattern with one column.
--
-- Additive and nullable, so every existing snapshot row keeps its exact previous
-- meaning: NULL means "this campaign was configured with no integration block",
-- which is what CampaignMapper writes for a campaign that never configured one.
-- That distinction is preserved deliberately - see CampaignConfigurationSnapshot.
-- It is NOT normalised to enabled=false / FULL, because "absent" and
-- "explicitly defaulted" are different facts and collapsing them here would
-- erase the difference before any runtime consumer could see it.

ALTER TABLE campaign_execution_configurations
    ADD COLUMN integration_config JSONB;

COMMENT ON COLUMN campaign_execution_configurations.integration_config IS
    'VB-7C.3: the campaign''s validated CampaignIntegrationConfig (webhook + report '
    'privacy), frozen at execution creation as its canonical JSON. NULL means the '
    'campaign configured no integration block, which is preserved verbatim and is '
    'distinct from an explicit default. Resource VALIDITY is not frozen, exactly as '
    'for every other referenced resource in this table.';
