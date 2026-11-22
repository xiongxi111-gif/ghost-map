package dev.ghostmap.core.join;

import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.Node;

import java.util.List;

/** Deterministic joined graph before snapshot version metadata is available. */
public record JoinedGraph(List<Node> nodes, List<FactNode> unresolvedNodes,
                          List<JoinedEdge> edges, List<Edge> dynamicEdges,
                          List<Diagnostic> diagnostics) {
    public JoinedGraph(List<Node> nodes, List<FactNode> unresolvedNodes,
                       List<JoinedEdge> edges, List<Diagnostic> diagnostics) {
        this(nodes, unresolvedNodes, edges, List.of(), diagnostics);
    }

    public JoinedGraph {
        if (nodes == null || unresolvedNodes == null || edges == null || dynamicEdges == null
                || diagnostics == null) {
            throw new NullPointerException("joined graph collections must not be null");
        }
        nodes = List.copyOf(nodes);
        unresolvedNodes = List.copyOf(unresolvedNodes);
        edges = List.copyOf(edges);
        dynamicEdges = List.copyOf(dynamicEdges);
        diagnostics = List.copyOf(diagnostics);
    }

    /** Edges with canonical endpoints that can enter a model snapshot. */
    public List<Edge> modelEdges() {
        return java.util.stream.Stream.concat(
                edges.stream().map(JoinedEdge::modelEdge).flatMap(java.util.Optional::stream),
                dynamicEdges.stream()).toList();
    }
}
