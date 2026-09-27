package com.shivang.obd.common.audit;

import java.util.Optional;

/**
 * SPI for resolving the current audit actor. Implemented outside common
 * (e.g. by the security layer for authenticated human users) so the shared
 * kernel stays domain-independent.
 *
 * <p>Actor semantics: an authenticated human is represented by its user
 * identifier; system/background processes (scheduler, billing, migration,
 * import) resolve no source and fall back to {@code SYSTEM} — they never
 * invent fake user identifiers.</p>
 */
public interface AuditorIdentitySource {

    Optional<String> currentActor();
}
