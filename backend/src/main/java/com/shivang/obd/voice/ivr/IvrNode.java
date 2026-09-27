package com.shivang.obd.voice.ivr;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One interaction step of an {@link IvrTree} (VB-6F).
 * <p>
 * A {@link IvrNodeType#MENU} node asks one question: it plays a prompt, waits
 * {@code inputWaitSeconds} for a single DTMF digit, and follows the transition
 * for that digit. A digit with no transition is an invalid input; no digit at all
 * within the wait window is a no-input. The two are separate outcomes with
 * separate prompts and separate retry budgets, because they are different things
 * that happened to a caller.
 * <p>
 * <b>Prompts are bare audio-asset UUIDs on purpose.</b> The {@code voice} module
 * must not depend on {@code audio} or {@code campaign} — it is a leaf, and
 * {@code DtmfInteraction} already references the campaign as a bare UUID for the
 * same reason. Ownership and approval are therefore checked through the existing
 * canonical authority, {@code CampaignResourceValidationService}, at
 * validation/snapshot time and again at playback time. That keeps one
 * authorization system instead of two, and keeps the module graph acyclic.
 * <p>
 * Retry counters are the number of <b>additional</b> attempts granted after the
 * initial one, so {@code 0} means one attempt in total. That is the same reading
 * as VB-6D.2's {@code maxAttempts} and is asserted by test, so it cannot be
 * misread as "total attempts".
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ivr_nodes", indexes = {
    @Index(name = "idx_ivr_nodes_tree", columnList = "tree_id, deleted_at")
})
public class IvrNode extends AuditableEntity {

    /** Owning tree. Tenant is reached through it, so nodes inherit tree-scoped isolation. */
    @Column(name = "tree_id", nullable = false)
    private UUID treeId;

    /**
     * Stable identifier, unique within the tree.
     * <p>
     * Part of the frozen execution contract, not a display label: the execution
     * snapshot keys its node map by this, and transitions are frozen as
     * target <em>keys</em>. That is what lets runtime traversal be a map lookup
     * with no live node reads.
     */
    @Column(name = "node_key", nullable = false, length = 64)
    private String nodeKey;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false)
    private IvrNodeType nodeType;

    /** The node's own prompt. Optional; a menu with no prompt simply asks silently. */
    @Column(name = "prompt_audio_asset_id")
    private UUID promptAudioAssetId;

    /** Played when the caller pressed a digit with no transition. */
    @Column(name = "invalid_prompt_audio_asset_id")
    private UUID invalidPromptAudioAssetId;

    /** Played when the wait window elapsed with no input. */
    @Column(name = "no_input_prompt_audio_asset_id")
    private UUID noInputPromptAudioAssetId;

    /**
     * How long to wait for one digit. Null means the runtime default.
     * <p>
     * Bounded to 1..120s, matching the existing DTMF collection window exactly,
     * so an IVR node can never ask a caller to wait longer than the
     * single-level runtime already may.
     */
    @Column(name = "input_wait_seconds")
    private Integer inputWaitSeconds;

    /** Additional attempts after the first on an invalid digit. 0 = one attempt total. */
    @Column(name = "invalid_input_retries", nullable = false)
    private Integer invalidInputRetries = 0;

    /** Additional attempts after the first on no input. 0 = one attempt total. */
    @Column(name = "no_input_retries", nullable = false)
    private Integer noInputRetries = 0;

    /** Required on TERMINAL nodes, forbidden on MENU nodes. Enforced by the validator. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_action", length = 40)
    private IvrTerminalAction terminalAction;

    /** Whether this node ends the flow when reached. */
    public boolean terminal() {
        return nodeType == IvrNodeType.TERMINAL;
    }
}
