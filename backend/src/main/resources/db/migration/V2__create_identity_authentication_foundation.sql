-- =====================================================================
-- Identity & authentication foundation (Phase 2A)
-- Adds canonical email uniqueness + password credential storage.
-- Forward-only; V1 remains untouched.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN normalized_email VARCHAR(255);

UPDATE users
SET normalized_email = lower(btrim(email));

ALTER TABLE users
    ALTER COLUMN normalized_email SET NOT NULL;

CREATE UNIQUE INDEX uq_users_normalized_email ON users (normalized_email);


CREATE TABLE user_credentials (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    identity_type   VARCHAR(30)  NOT NULL DEFAULT 'PASSWORD'
                    CONSTRAINT ck_user_credentials_identity_type CHECK (identity_type IN ('PASSWORD')),
    credential_hash VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_user_credentials_user_type UNIQUE (user_id, identity_type)
);

CREATE INDEX idx_user_credentials_user ON user_credentials (user_id);
