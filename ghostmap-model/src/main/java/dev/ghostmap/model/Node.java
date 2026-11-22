package dev.ghostmap.model;

import dev.ghostmap.model.CanonicalId.TypeId;

import java.util.Map;
import java.util.Optional;

/** An immutable graph node with canonical identity and optional source location. */
public record Node(CanonicalId id, NodeKind kind, String name, Optional<TypeId> owner,
                   Map<String, JsonValue> attrs, Optional<SourceLocation> location) {
    public Node {
        if (id == null) {
            throw new NullPointerException("id");
        }
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        if (id.kind() != kind) {
            throw new IllegalArgumentException("node kind does not match canonical id");
        }
        ModelChecks.nonBlank(name, "name");
        if (owner == null) {
            throw new NullPointerException("owner");
        }
        attrs = ModelChecks.immutableMap(attrs, "attrs");
        attrs.keySet().forEach(key -> ModelChecks.nonBlank(key, "attribute key"));
        if (location == null) {
            throw new NullPointerException("location");
        }
    }
}
