package com.shivang.obd.common.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.AuditorAware;

/**
 * Resolves the audit actor for JPA auditing. Consults every registered
 * {@link AuditorIdentitySource}; the first present actor wins. When no
 * source resolves an actor (anonymous, background jobs, startup), the
 * conservative {@code SYSTEM} actor is used — never a fabricated user id.
 */
public class AuditorProvider implements AuditorAware<String> {

    public static final String SYSTEM_ACTOR = "SYSTEM";

    private final List<AuditorIdentitySource> sources;

    public AuditorProvider(List<AuditorIdentitySource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    @Override
    public Optional<String> getCurrentAuditor() {
        return sources.stream()
            .map(source -> source.currentActor())
            .flatMap(Optional::stream)
            .filter(actor -> actor != null && !actor.isBlank())
            .findFirst()
            .or(() -> Optional.of(SYSTEM_ACTOR));
    }
}
