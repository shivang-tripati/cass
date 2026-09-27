-- =====================================================================
-- VB-4F hardening: reservation-uniqueness invariant (forward-only).
-- =====================================================================
-- The reservation lifecycle treats "one live (non-RELEASED) hold per call
-- session" as an invariant: releaseForCallSession resolves the hold via a
-- single-row lookup keyed on call_session_id, and every cleanup path
-- (caller/agent hangup, originate failure, ACD expiry) releases by
-- call_session_id. A service bug or race that produced two live holds for
-- the same session would therefore leak an agent slot forever (the second
-- hold would never be released). This partial unique index makes the
-- invariant a database guarantee, matching the established pattern of
-- uq_queue_waiting_calls_active_session (V37).
--
-- Replaces the plain session index idx_agent_reservations_session, which
-- the lookup query uses.
-- =====================================================================

DROP INDEX IF EXISTS idx_agent_reservations_session;

CREATE UNIQUE INDEX uq_agent_reservations_live_session
    ON agent_reservations (call_session_id) WHERE status <> 'RELEASED';
