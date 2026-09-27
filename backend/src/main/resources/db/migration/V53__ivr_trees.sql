-- =====================================================================
-- VB-6F — Reusable IVR trees + multi-level DTMF execution steps
-- Forward-only; V1-V52 remain untouched.
--
-- WHAT THIS IS NOT: this is not a new telephony engine and not a second DTMF
-- runtime. VB-2 already ships a working single-level DTMF runtime, and it is
-- reused unchanged. VB-6F adds the three things that runtime structurally
-- cannot express:
--
--   1. A REUSABLE tree resource, so an IVR flow is authored once and shared
--      by any number of campaigns instead of being embedded in each
--      campaign's type_config JSONB.
--   2. A PER-STEP state table (ivr_steps), because dtmf_interactions is
--      one-row-per-call-session and therefore cannot represent progress
--      through a multi-level tree. dtmf_interactions is NOT modified.
--   3. Per-node invalid-input and no-input retry, which the single-level
--      runtime explicitly does not have.
--
-- NO changes to: dtmf_interactions, call_sessions, call_attempts,
-- campaigns, or campaign_execution_configurations. The frozen IVR tree rides
-- the EXISTING type_config JSONB column, which is already updatable=false, so
-- no IVR versioning, history table, or snapshot table is introduced.
--
-- TENANT + TREE ISOLATION is enforced at the database layer, not only in
-- Java:
--
--   * ivr_nodes.tree_id REFERENCES ivr_trees(id)
--   * ivr_transitions.node_id REFERENCES ivr_nodes(id)
--   * ivr_transitions ALSO carries tree_id, and the pair
--     (tree_id, node_id) is a composite FK to ivr_nodes(id, tree_id), while
--     (tree_id, target_node_id) is a composite FK to the same pair.
--     A transition therefore CANNOT point at a node in another tree: the
--     database rejects the row outright. This is the guarantee the audit
--     called for, and it is why ivr_transitions carries tree_id despite
--     being derivable through its parent node — the redundancy is the
--     constraint.
--
-- IVR CYCLES are rejected by validation, not by the schema (a cycle needs
-- more than FKs to express). The platform models a finite TREE: traversal
-- is bounded, and "return to a previous menu" is a transition to any node
-- within the same tree.
-- =====================================================================

CREATE TYPE ivr_tree_status AS ENUM
    ('DRAFT', 'ACTIVE', 'ARCHIVED');

CREATE TYPE ivr_node_type AS ENUM
    ('MENU', 'TERMINAL');

CREATE TYPE ivr_terminal_action AS ENUM
    ('TERMINATE', 'CONNECT_BY_AGENT');

CREATE TYPE ivr_step_result_type AS ENUM
    ('WAITING_INPUT', 'ADVANCED', 'INVALID_RETRY', 'NO_INPUT_RETRY',
     'TERMINAL_REACHED', 'ABANDONED');

-- ---------------------------------------------------------------------
-- ivr_trees: the reusable resource
-- ---------------------------------------------------------------------
CREATE TABLE ivr_trees (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    name                    VARCHAR(120) NOT NULL,
    description             VARCHAR(512),
    status                  ivr_tree_status NOT NULL DEFAULT 'DRAFT',
    -- The single root node. Nullable in the column so a tree can be
    -- INSERTed before its nodes exist (a tree and its root are created in
    -- one transaction by the service); ON DELETE SET NULL so soft-deleting
    -- a node cannot violate the FK, while validation refuses to ACTIVATE a
    -- tree whose root is absent.
    root_node_id            UUID,
    -- Where this tree came from, when it was produced by the
    -- create-IVR-from-campaign conversion. Purely informational and never
    -- used to reverse the conversion; the campaign's own type_config is the
    -- authority for what the campaign now references.
    source_campaign_id      UUID REFERENCES campaigns (id),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255),
    CONSTRAINT ck_ivr_trees_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX idx_ivr_trees_tenant  ON ivr_trees (tenant_id, deleted_at);
CREATE INDEX idx_ivr_trees_status  ON ivr_trees (tenant_id, status, deleted_at);

-- ---------------------------------------------------------------------
-- ivr_nodes: one interaction/menu step
-- ---------------------------------------------------------------------
-- ivr_nodes(id, tree_id) is the composite key referenced by the transition
-- FKs below, so it needs an explicit unique constraint.
CREATE TABLE ivr_nodes (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tree_id                     UUID NOT NULL REFERENCES ivr_trees (id),
    -- Stable, human-meaningful identifier unique within the tree. The
    -- execution snapshot keys its node map by this, so it is part of the
    -- frozen execution contract, not a display label.
    node_key                    VARCHAR(64) NOT NULL,
    node_type                   ivr_node_type NOT NULL,
    -- Prompt audio assets. Bare UUIDs on purpose: the voice module must not
    -- depend on the audio or campaign modules, so ownership/approval is
    -- validated through the canonical campaign resource authority at
    -- snapshot time and at playback time, not by a foreign key that would
    -- couple this table to another module's lifecycle.
    prompt_audio_asset_id       UUID,
    invalid_prompt_audio_asset_id UUID,
    no_input_prompt_audio_asset_id UUID,
    -- How long to wait for one digit at this node. Null = the runtime
    -- default; bounds mirror the existing DTMF window (1..120s) so an IVR
    -- node can never ask the caller to wait longer than a DTMF collection
    -- window already may.
    input_wait_seconds          SMALLINT,
    -- ADDITIONAL attempts granted after the initial one: 0 means one
    -- attempt in total. The same "retries after the first" reading as
    -- VB-6D.2's maxAttempts, documented on the DTOs and asserted by test.
    invalid_input_retries       SMALLINT NOT NULL DEFAULT 0,
    no_input_retries            SMALLINT NOT NULL DEFAULT 0,
    -- Required on TERMINAL nodes, forbidden on MENU nodes (enforced by the
    -- service validator; a CHECK cannot see the node's own type row).
    terminal_action             ivr_terminal_action,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(255),
    updated_by                  VARCHAR(255),
    deleted_at                  TIMESTAMPTZ,
    deleted_by                  VARCHAR(255),
    CONSTRAINT uq_ivr_nodes_tree_key UNIQUE (tree_id, node_key),
    CONSTRAINT ck_ivr_nodes_key_not_blank  CHECK (btrim(node_key) <> ''),
    CONSTRAINT ck_ivr_nodes_wait_seconds
        CHECK (input_wait_seconds IS NULL OR input_wait_seconds BETWEEN 1 AND 120),
    CONSTRAINT ck_ivr_nodes_invalid_retries
        CHECK (invalid_input_retries BETWEEN 0 AND 10),
    CONSTRAINT ck_ivr_nodes_no_input_retries
        CHECK (no_input_retries BETWEEN 0 AND 10)
);

-- Referenced by ivr_transitions' composite FKs.
CREATE UNIQUE INDEX uq_ivr_nodes_id_tree ON ivr_nodes (id, tree_id);
CREATE INDEX idx_ivr_nodes_tree ON ivr_nodes (tree_id, deleted_at);

-- ---------------------------------------------------------------------
-- ivr_transitions: one DTMF digit on one node to a target in the SAME tree
-- ---------------------------------------------------------------------
CREATE TABLE ivr_transitions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Redundant with the parent node's tree, and deliberately so: it is what
    -- lets the database prove a target belongs to the same tree.
    tree_id             UUID NOT NULL REFERENCES ivr_trees (id),
    node_id             UUID NOT NULL,
    -- FreeSWITCH DTMF alphabet, matching the existing runtime's
    -- DtmfConfig.isCollectableDigit exactly. No invented semantics.
    dtmf_input          VARCHAR(1) NOT NULL,
    target_node_id      UUID NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    deleted_at          TIMESTAMPTZ,
    deleted_by          VARCHAR(255),
    CONSTRAINT fk_ivr_transitions_node
        FOREIGN KEY (node_id, tree_id) REFERENCES ivr_nodes (id, tree_id),
    CONSTRAINT fk_ivr_transitions_target_same_tree
        FOREIGN KEY (target_node_id, tree_id) REFERENCES ivr_nodes (id, tree_id),
    -- One digit maps to exactly one transition, per node. This is the
    -- "same input cannot map to multiple transitions" rule, enforced by the
    -- database as well as by validation.
    CONSTRAINT uq_ivr_transitions_node_input UNIQUE (node_id, dtmf_input),
    CONSTRAINT ck_ivr_transitions_dtmf_input
        CHECK (dtmf_input ~ '^[0-9*#]$'),
    CONSTRAINT ck_ivr_transitions_no_self_loop
        CHECK (node_id <> target_node_id)
);

CREATE INDEX idx_ivr_transitions_node ON ivr_transitions (node_id, deleted_at);
CREATE INDEX idx_ivr_transitions_target ON ivr_transitions (target_node_id, deleted_at);

-- Now that the target FK exists, the trees' root pointer can be constrained
-- to a node of that same tree.
ALTER TABLE ivr_trees
    ADD CONSTRAINT fk_ivr_trees_root_same_tree
        FOREIGN KEY (root_node_id, id) REFERENCES ivr_nodes (id, tree_id)
        DEFERRABLE INITIALLY DEFERRED;

-- ---------------------------------------------------------------------
-- ivr_steps: per-node live state for one active call
-- ---------------------------------------------------------------------
-- One row per node VISIT. This is the IVR analogue of dtmf_interactions and
-- the reason VB-6F needs a table at all: dtmf_interactions is one row per
-- call session, so it structurally cannot record which node of a multi-level
-- tree the caller is currently on, nor per-node retry counters, nor a
-- per-node deadline.
--
-- The current node of a call is the newest non-terminal row for its session,
-- which mirrors exactly how the current DTMF interaction is found. No IVR
-- state is written to call_sessions, so there is no second source of truth.
CREATE TABLE ivr_steps (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    call_session_id     UUID NOT NULL REFERENCES call_sessions (id),
    call_attempt_id     UUID REFERENCES call_attempts (id),
    execution_id        UUID,
    tree_id             UUID NOT NULL REFERENCES ivr_trees (id),
    -- Frozen node key. The live node is never re-read from ivr_nodes during
    -- traversal: this column names the key inside the immutable execution
    -- snapshot, which is the whole point of the snapshot.
    node_key            VARCHAR(64) NOT NULL,
    node_type           ivr_node_type NOT NULL,
    -- Frozen copy of the node's wait, so a live IVR edit cannot extend or
    -- shorten a deadline a caller is already waiting on.
    input_wait_seconds  SMALLINT NOT NULL,
    -- The node's frozen input budgets, so a step is SELF-DESCRIBING. The
    -- per-node timeout path is dispatched with only a session id; rebuilding
    -- the node from the snapshot there would mean resolving the execution on the
    -- timeout path, and reading live IVR rows there would defeat the snapshot
    -- guarantee. dtmf_interactions freezes its whole configuration for the same
    -- reason.
    invalid_input_retries SMALLINT NOT NULL DEFAULT 0,
    no_input_retries     SMALLINT NOT NULL DEFAULT 0,
    -- Retry counters. Max values 10+10 retries (see ck bounds below).
    invalid_attempts    SMALLINT NOT NULL DEFAULT 0,
    no_input_attempts   SMALLINT NOT NULL DEFAULT 0,
    result              ivr_step_result_type NOT NULL DEFAULT 'WAITING_INPUT',
    result_reason       VARCHAR(255),
    result_at           TIMESTAMPTZ,
    -- Deterministic per-node timeout source, consumed by the EXISTING
    -- 1-second DtmfTimeoutScheduler scan. No new scheduler.
    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    deleted_at          TIMESTAMPTZ,
    deleted_by          VARCHAR(255),
    CONSTRAINT ck_ivr_steps_key_not_blank CHECK (btrim(node_key) <> ''),
    CONSTRAINT ck_ivr_steps_invalid_attempts
        CHECK (invalid_attempts BETWEEN 0 AND 11),
    CONSTRAINT ck_ivr_steps_no_input_attempts
        CHECK (no_input_attempts BETWEEN 0 AND 11)
);

CREATE INDEX idx_ivr_steps_session   ON ivr_steps (call_session_id, deleted_at);
CREATE INDEX idx_ivr_steps_tenant    ON ivr_steps (tenant_id, deleted_at);
-- The existing timeout poller's access path: non-terminal steps past their
-- per-node deadline.
CREATE INDEX idx_ivr_steps_timeout   ON ivr_steps (result, expires_at);
CREATE INDEX idx_ivr_steps_attempt   ON ivr_steps (call_attempt_id);
