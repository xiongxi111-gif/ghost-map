package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.EdgeKind;

import java.util.List;
import java.util.Optional;

/** Explicit unresolved annotation injection with zero or ambiguous candidates. */
public record UnresolvedWiringFact(SymbolId from, WiringInjectionPoint point, String requiredType,
                                   Optional<String> requestedName, List<BeanId> candidates,
                                   String classFile, int line) implements EdgeFact {
    public UnresolvedWiringFact {
        if (from == null || point == null || requiredType == null || requestedName == null
                || candidates == null || classFile == null) {
            throw new NullPointerException("unresolved wiring fields must not be null");
        }
        candidates = List.copyOf(candidates);
        if (requiredType.isBlank() || classFile.isBlank() || line < 1) {
            throw new IllegalArgumentException("unresolved wiring requires type and location evidence");
        }
    }

    @Override
    public EdgeKind kind() {
        return EdgeKind.UNRESOLVED;
    }
}
