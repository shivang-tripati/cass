-- =====================================================================
-- Inbound DID destination (Phase VB-4D)
-- Forward-only; V1-V38 remain untouched.
-- =====================================================================
-- Inbound routing reuses the canonical voice/contact-center domain:
-- CallSession + CUSTOMER/AGENT CallLegs + QueueWaitingCall + ACD. The
-- ONLY schema gap is that a DID cannot yet express "where do inbound
-- calls to this number go". This migration adds that configuration to
-- the existing dids table — no new inbound-call aggregate is created.
--
-- Semantics:
--   inbound_destination = 'QUEUE'  → inbound_queue_id  required
--   inbound_destination = 'AGENT'  → inbound_agent_id  required
--   inbound_destination = NULL     → DID does not accept inbound calls
--     (default for every existing/outbound DID — behavior unchanged)
--
-- Kind/pointer coherence is enforced by the routing service (fail
-- closed): a missing/mismatched pointer makes the DID unroutable.
-- The DB keeps FKs with ON DELETE SET NULL so referential cleanup can
-- never wedge, and a nulled pointer simply means "not routable".
--
-- Tenant isolation is enforced by FKs scoped to the DID's own tenant:
-- a DID owned by tenant A can only ever reference a queue/agent of
-- tenant A. Cross-tenant destination references are impossible at the
-- schema level. Pool numbers (tenant_id NULL, platform-owned) cannot
-- carry an inbound destination at all — inbound routing is a
-- tenant-scoped capability.
-- =====================================================================

CREATE TYPE did_inbound_destination AS ENUM ('QUEUE', 'AGENT');

ALTER TABLE dids
    ADD COLUMN inbound_destination did_inbound_destination,
    ADD COLUMN inbound_queue_id    UUID REFERENCES queues (id) ON DELETE SET NULL,
    ADD COLUMN inbound_agent_id    UUID REFERENCES agents (id) ON DELETE SET NULL;

ALTER TABLE dids
    ADD CONSTRAINT ck_dids_inbound_same_tenant_queue CHECK (
        inbound_queue_id IS NULL OR tenant_id IS NOT NULL
    ),
    ADD CONSTRAINT ck_dids_inbound_same_tenant_agent CHECK (
        inbound_agent_id IS NULL OR tenant_id IS NOT NULL
    );

CREATE INDEX idx_dids_inbound_destination ON dids (inbound_destination);
CREATE INDEX idx_dids_inbound_queue ON dids (inbound_queue_id);
CREATE INDEX idx_dids_inbound_agent ON dids (inbound_agent_id);
