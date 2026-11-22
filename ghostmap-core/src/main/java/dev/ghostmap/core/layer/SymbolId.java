package dev.ghostmap.core.layer;

import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.NodeKind;

/** Canonical or explicitly provisional identity emitted by a layer. */
public sealed interface SymbolId permits SymbolId.Canonical, ProvisionalId {
    NodeKind kind();

    String value();

    boolean provisional();

    record Canonical(CanonicalId id) implements SymbolId {
        public Canonical {
            if (id == null) {
                throw new NullPointerException("id");
            }
        }

        @Override
        public NodeKind kind() {
            return id.kind();
        }

        @Override
        public String value() {
            return id.value();
        }

        @Override
        public boolean provisional() {
            return false;
        }

        @Override
        public String toString() {
            return value();
        }
    }
}
