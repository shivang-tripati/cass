/**
 * IVR tree authoring and lifecycle.
 *
 * <p>A resource module in the same shape as {@code tts} and {@code audio}: it
 * owns a tenant-scoped resource with its own lifecycle and REST surface, and it
 * depends on the {@code voice} domain plus {@code authz}. It knows nothing about
 * campaigns.
 * <p>
 * That last point is the load-bearing one. The campaign side — attaching a tree,
 * capturing an immutable snapshot, running the flow — lives in
 * {@code campaign} and depends on <em>this</em> module, which is the only
 * direction that keeps the graph acyclic. Prompt-resource governance is the one
 * thing this module cannot do alone, because the authority it needs
 * ({@code CampaignResourceValidationService}) lives in {@code campaign}; that is
 * reached through the {@link com.shivang.obd.ivr.IvrPromptChecker} port, the same
 * port/adapter shape the DTMF runtime and the agent-connect boundary already use.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "IVR Module"
)
package com.shivang.obd.ivr;
