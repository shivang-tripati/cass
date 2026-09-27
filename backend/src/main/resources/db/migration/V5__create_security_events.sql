-- =====================================================================
-- Security event foundation (Phase 2B.4A)
-- Append-only audit trail for the authentication/token lifecycle.
-- user_id is deliberately nullable (unknown-email LOGIN_FAILURE) and
-- carries NO foreign key so events survive user deletion and can be
-- recorded for nonexistent identities. Forward-only; V1-V4 untouched.
-- =====================================================================

CREATE TABLE security_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID,
    event_type  VARCHAR(50)  NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    request_id  VARCHAR(64),
    ip_address  VARCHAR(64),
    user_agent  VARCHAR(512),
    success     BOOLEAN      NOT NULL,
    metadata    JSONB
);

CREATE INDEX idx_security_events_user ON security_events (user_id);
CREATE INDEX idx_security_events_type ON security_events (event_type);
CREATE INDEX idx_security_events_occurred_at ON security_events (occurred_at);
