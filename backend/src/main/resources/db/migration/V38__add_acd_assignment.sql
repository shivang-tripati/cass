-- =====================================================================
-- ACD assignment + reservation-ownership/timeout columns (Phase VB-4C)
-- Forward-only; V1-V37 remain untouched.
-- =====================================================================
-- VB-4C is a control-plane decision/reservation layer: it determines and
-- reserves an agent for a waiting call. It performs NO telephony (no
-- legs, no originate, no bridge) — VB-4D consumes the assignment.
--
-- Reuse over new concepts:
--   * agent_reservations (VB-3) stays the ONLY reservation mechanism.
--     ACD adds ownership (queue_id / waiting_call_id) and a bounded
--     hold window (expires_at) to the existing lifecycle
--     RESERVED -> ACTIVE -> RELEASED (STALE_RECLAIM reuses release).
--   * queue_waiting_calls (VB-4B) stays the only waiting-call
--     representation. Assignment = WAITING -> ASSIGNED (new enum value)
--     plus the assigned agent/reservation reference. No CallLeg is
--     created by ACD.
--   * Queue timeout executes VB-4B's persisted max_wait_seconds via the
--     waiting row's expires_at snapshot (WAITING -> ABANDONED).
--     Overflow executes VB-4B's overflow_enabled/overflow_queue_id
--     configuration (single bounded hop, target must be ACTIVE).

-- --- reservation ownership + bounded hold window -----------------------
-- ACD reservations are attributable to a queue/waiting call and expire if
-- the consuming (VB-4D) flow never claims them. NULL queue_id/waiting_call_id
-- means a non-ACD reservation (VB-3 CONNECT_BY_AGENT) — those keep the
-- existing stale-reconciler behavior and have no expiry.
ALTER TABLE agent_reservations
    ADD COLUMN queue_id        UUID REFERENCES queues (id),
    ADD COLUMN waiting_call_id UUID REFERENCES queue_waiting_calls (id),
    ADD COLUMN expires_at      TIMESTAMPTZ;

-- --- waiting-call lifecycle: ASSIGNED (ACD claimed an agent) ----------
ALTER TYPE queue_waiting_call_status ADD VALUE IF NOT EXISTS 'ASSIGNED'
    AFTER 'WAITING';

ALTER TABLE queue_waiting_calls
    ADD COLUMN assigned_agent_id       UUID REFERENCES agents (id),
    ADD COLUMN assigned_reservation_id UUID REFERENCES agent_reservations (id),
    ADD COLUMN assigned_at             TIMESTAMPTZ;

-- Index for "the reservations ACD owns" scans (expiry/stale sweeps) and
-- assignment lookups.
CREATE INDEX idx_agent_reservations_queue
    ON agent_reservations (queue_id) WHERE queue_id IS NOT NULL;
CREATE INDEX idx_agent_reservations_waiting_call
    ON agent_reservations (waiting_call_id) WHERE waiting_call_id IS NOT NULL;
CREATE INDEX idx_agent_reservations_expiry_scan
    ON agent_reservations (status, expires_at);
CREATE INDEX idx_queue_waiting_calls_assignment
    ON queue_waiting_calls (assigned_reservation_id)
    WHERE assigned_reservation_id IS NOT NULL;
