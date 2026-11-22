package dev.ghostmap.core.layer;

import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;

import java.util.Map;
import java.util.Optional;

/** Declaration node emitted before cross-layer identity reconciliation. */
public record FactNode(SymbolId id, NodeKind kind, String name, Optional<TypeId> owner,
                       Map<String, JsonValue> attrs, SourceLocation location) {
    public FactNode {
        if (id == null || kind == null || name == null || owner == null || attrs == null || location == null) {
            throw new NullPointerException("fact node fields must not be null");
        }
        if (id.kind() != kind) {
            throw new IllegalArgumentException("node kind does not match symbol id");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        attrs = Map.copyOf(attrs);
    }
}
