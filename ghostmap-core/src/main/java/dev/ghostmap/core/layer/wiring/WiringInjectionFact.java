package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.Evidence;

import java.util.List;

/** Resolved XML bean-to-bean injection with inheritance evidence. */
public record WiringInjectionFact(SymbolId from, SymbolId target, WiringInjectionPoint point,
                                  Evidence.Wiring evidence, List<BeanId> inheritanceChain) implements EdgeFact {
    public WiringInjectionFact {
        if (from == null || target == null || point == null || evidence == null || inheritanceChain == null) {
            throw new NullPointerException("wiring injection fields must not be null");
        }
        if (!(from instanceof SymbolId.Canonical source) || !(source.id() instanceof BeanId sourceBean)
                || !(target instanceof SymbolId.Canonical destination)
                || !(destination.id() instanceof BeanId targetBean)) {
            throw new IllegalArgumentException("wiring injection endpoints must be canonical bean ids");
        }
        if (!evidence.bean().equals(targetBean)) {
            throw new IllegalArgumentException("wiring evidence must identify the referenced bean");
        }
        inheritanceChain = List.copyOf(inheritanceChain);
    }

    @Override
    public EdgeKind kind() {
        return EdgeKind.INJECTS;
    }
}
