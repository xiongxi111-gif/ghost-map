package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.Evidence;

import java.util.List;

/** XML bean-to-implementation binding, including inherited class evidence. */
public record WiringBindFact(SymbolId from, SymbolId target, Evidence.Wiring evidence,
                             List<BeanId> inheritanceChain) implements EdgeFact {
    public WiringBindFact {
        if (from == null || target == null || evidence == null || inheritanceChain == null) {
            throw new NullPointerException("wiring bind fields must not be null");
        }
        if (!(from instanceof SymbolId.Canonical source) || !(source.id() instanceof BeanId sourceBean)
                || !(target instanceof SymbolId.Canonical destination)
                || !(destination.id() instanceof TypeId)) {
            throw new IllegalArgumentException("wiring bind endpoints must be a canonical bean and type");
        }
        if (!evidence.bean().equals(sourceBean)) {
            throw new IllegalArgumentException("wiring evidence must identify the bound bean");
        }
        inheritanceChain = List.copyOf(inheritanceChain);
    }

    @Override
    public EdgeKind kind() {
        return EdgeKind.BINDS;
    }
}
