/**
 * Reusable IVR domain: trees, nodes, transitions, the execution snapshot, the
 * per-call step state, and the pure structural validator.
 *
 * <p>Exposed as a named interface because the {@code ivr} module — the tenant
 * resource surface for authoring trees — and the {@code campaign} module, which
 * captures snapshots and runs calls, both consume it. Declaring the exposure
 * explicitly is what makes that a stated design decision rather than an
 * accident Modulith happens to tolerate.
 */
@org.springframework.modulith.NamedInterface("ivr")
package com.shivang.obd.voice.ivr;
