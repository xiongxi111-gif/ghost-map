package dev.ghostmap.core.join;

import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.Node;

import java.util.List;

/** Deterministic L1/L2 join result before snapshot version metadata is available. */
public record JoinedGraph(List<Node> nodes, List<FactNode> unresolvedNodes,
                          List<JoinedEdge> edges, List<Diagnostic> diagnostics) {
    public JoinedGraph {
        if (nodes == null || unresolvedNodes == null || edges == null || diagnostics == null) {
            throw new NullPointerException("joined graph collections must not be null");
        }
        nodes = List.copyOf(nodes);
        unresolvedNodes = List.copyOf(unresolvedNodes);
        edges = List.copyOf(edges);
        diagnostics = List.copyOf(diagnostics);
    }

    /** Edges that already have canonical endpoints and can enter a model snapshot. */
    public List<Edge> modelEdges() {
        return edges.stream().map(JoinedEdge::modelEdge).flatMap(java.util.Optional::stream).toList();
    }
}
