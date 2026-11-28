package dev.ghostmap.core.join;

import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.core.layer.runtime.RuntimePair;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Mutable state owned by one JoinPolicy invocation; rules retain no global state. */
public final class JoinContext {
    private final List<NodeFact> nodeFacts;
    private final List<EdgeFactEntry> edgeFacts;
    private final Set<LayerId> layers;
    private final Map<String, CanonicalId> identities = new HashMap<>();
    private final Set<CanonicalId> potentialSourceIdentities = new HashSet<>();
    private final Set<RuntimePair> consumedRuntimePairs = new HashSet<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private List<Node> nodes = List.of();
    private List<FactNode> unresolvedNodes = List.of();
    private List<JoinedEdge> edges = List.of();
    private List<dev.ghostmap.model.Edge> dynamicEdges = List.of();

    public JoinContext(List<LayerFacts> facts) {
        if (facts == null) {
            throw new NullPointerException("facts");
        }
        List<NodeFact> collectedNodes = new ArrayList<>();
        List<EdgeFactEntry> collectedEdges = new ArrayList<>();
        Set<LayerId> collectedLayers = EnumSet.noneOf(LayerId.class);
        for (LayerFacts layerFacts : facts) {
            if (layerFacts == null) {
                throw new NullPointerException("layer facts");
            }
            collectedLayers.add(layerFacts.layer());
            layerFacts.nodes().forEach(node -> collectedNodes.add(new NodeFact(layerFacts.layer(), node)));
            layerFacts.edges().forEach(edge -> collectedEdges.add(new EdgeFactEntry(layerFacts.layer(), edge)));
            diagnostics.addAll(layerFacts.diagnostics());
        }
        nodeFacts = collectedNodes.stream().sorted(Comparator
                .comparing((NodeFact fact) -> fact.layer().ordinal())
                .thenComparing(fact -> fact.node().id().value())
                .thenComparing(fact -> fact.node().sourceLocation().map(location -> location.file()).orElse(""))
                .thenComparingInt(fact -> fact.node().sourceLocation().map(location -> location.line()).orElse(0))
                .thenComparing(fact -> fact.node().name())
                .thenComparing(fact -> canonicalAttributes(fact.node())))
                .toList();
        edgeFacts = collectedEdges.stream().sorted(Comparator
                .comparing((EdgeFactEntry fact) -> fact.layer().ordinal())
                .thenComparing(fact -> fact.fact().getClass().getName())
                .thenComparing(fact -> fact.fact().from().value())
                .thenComparing(fact -> fact.fact().toString()))
                .toList();
        layers = Set.copyOf(collectedLayers);
    }

    public List<NodeFact> nodeFacts() {
        return nodeFacts;
    }

    public List<EdgeFactEntry> edgeFacts() {
        return edgeFacts;
    }

    public boolean hasLayer(LayerId layer) {
        return layers.contains(layer);
    }

    public void mapIdentity(SymbolId source, CanonicalId canonical) {
        identities.put(source.value(), canonical);
    }

    public Optional<CanonicalId> resolve(SymbolId symbol) {
        if (symbol instanceof SymbolId.Canonical canonical) {
            return Optional.of(canonical.id());
        }
        return Optional.ofNullable(identities.get(symbol.value()));
    }

    public Map<String, CanonicalId> identities() {
        return Map.copyOf(identities);
    }

    public void markPotentialSourceIdentity(CanonicalId canonical) {
        potentialSourceIdentities.add(canonical);
    }

    public Set<CanonicalId> potentialSourceIdentities() {
        return Set.copyOf(potentialSourceIdentities);
    }

    public void markRuntimeConsumed(RuntimePair pair) {
        consumedRuntimePairs.add(pair);
    }

    public boolean runtimeConsumed(RuntimePair pair) {
        return consumedRuntimePairs.contains(pair);
    }

    public List<Node> nodes() {
        return nodes;
    }

    public void setNodes(List<Node> updatedNodes) {
        nodes = List.copyOf(updatedNodes);
    }

    public List<FactNode> unresolvedNodes() {
        return unresolvedNodes;
    }

    public void setUnresolvedNodes(List<FactNode> updatedNodes) {
        unresolvedNodes = List.copyOf(updatedNodes);
    }

    public List<JoinedEdge> edges() {
        return edges;
    }

    public void setEdges(List<JoinedEdge> updatedEdges) {
        edges = List.copyOf(updatedEdges);
    }

    public List<dev.ghostmap.model.Edge> dynamicEdges() {
        return dynamicEdges;
    }

    public void setDynamicEdges(List<dev.ghostmap.model.Edge> updatedEdges) {
        dynamicEdges = List.copyOf(updatedEdges);
    }

    public void addDiagnostic(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    public JoinedGraph result() {
        List<Node> sortedNodes = nodes.stream().sorted(Comparator.comparing(node -> node.id().value())).toList();
        List<FactNode> sortedUnresolved = unresolvedNodes.stream()
                .sorted(Comparator.comparing(node -> node.id().value())).toList();
        List<JoinedEdge> sortedEdges = edges.stream().sorted(Comparator
                .comparing((JoinedEdge edge) -> edge.id().value())
                .thenComparing(edge -> edge.target().map(CanonicalId::value).orElse("")))
                .toList();
        List<dev.ghostmap.model.Edge> sortedDynamicEdges = dynamicEdges.stream()
                .sorted(Comparator.comparing(edge -> edge.id().value())).toList();
        List<Diagnostic> sortedDiagnostics = diagnostics.stream().sorted(Comparator
                .comparing((Diagnostic diagnostic) -> diagnostic.code().name())
                .thenComparing(Diagnostic::file)
                .thenComparingInt(Diagnostic::line)
                .thenComparing(Diagnostic::message))
                .toList();
        return new JoinedGraph(sortedNodes, sortedUnresolved, sortedEdges, sortedDynamicEdges,
                sortedDiagnostics);
    }

    private static String canonicalAttributes(FactNode node) {
        return node.attrs().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + '=' + entry.getValue()).reduce("", (left, right) -> left + '|' + right);
    }

    public record NodeFact(LayerId layer, FactNode node) {
        public NodeFact {
            if (layer == null || node == null) {
                throw new NullPointerException("node fact fields must not be null");
            }
        }
    }

    public record EdgeFactEntry(LayerId layer, EdgeFact fact) {
        public EdgeFactEntry {
            if (layer == null || fact == null) {
                throw new NullPointerException("edge fact fields must not be null");
            }
        }
    }
}
