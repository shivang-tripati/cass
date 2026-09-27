package com.shivang.obd.voice.ivr;

/**
 * Lifecycle of a reusable IVR tree (VB-6F).
 * <p>
 * Mirrors the existing resource lifecycle conventions
 * ({@code TtsTemplateStatus}, {@code CampaignStatus}): a small typed enum,
 * never a free string.
 * <p>
 * Only {@link #ACTIVE} trees may be attached to a campaign or captured into an
 * execution snapshot. {@link #ARCHIVED} is deliberately <em>not</em> a delete:
 * a captured snapshot is self-contained JSONB, so executions created while a
 * tree was active remain fully executable and auditable after it is archived.
 */
public enum IvrTreeStatus {

    /** Editable. Not selectable for execution, so a work in progress cannot be dialled. */
    DRAFT,

    /** Selectable for execution and captured into new execution snapshots. */
    ACTIVE,

    /** No longer selectable. Existing snapshots keep working; the tree is retained for audit. */
    ARCHIVED
}
