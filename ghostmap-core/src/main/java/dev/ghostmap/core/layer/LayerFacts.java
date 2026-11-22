package dev.ghostmap.core.layer;

import dev.ghostmap.model.LayerId;

import java.util.List;

/** Immutable facts and diagnostics emitted by one analysis layer. */
public record LayerFacts(LayerId layer, List<FactNode> nodes, List<EdgeFact> edges,
                         List<Diagnostic> diagnostics) {
    public LayerFacts {
        if (layer == null || nodes == null || edges == null || diagnostics == null) {
            throw new NullPointerException("layer facts must not contain null collections or metadata");
        }
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        diagnostics = List.copyOf(diagnostics);
    }
}
