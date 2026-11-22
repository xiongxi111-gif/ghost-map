package dev.ghostmap.mcp;

import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.Node;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read-only indexes and response projections for one loaded snapshot. */
final class SnapshotIndex {
    private final Snapshot snapshot;
    private final Map<String, Node> nodesById;
    private final Map<CanonicalId, List<Edge.Resolved>> outgoingWiringEdges;
    private final Map<CanonicalId, List<Edge.Resolved>> incomingInjectionEdges;

    SnapshotIndex(Snapshot snapshot) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot");
        }
        this.snapshot = snapshot;
        Map<String, Node> indexedNodes = new LinkedHashMap<>();
        for (Node node : snapshot.nodes()) {
            indexedNodes.put(node.id().value(), node);
        }
        this.nodesById = Map.copyOf(indexedNodes);
        this.outgoingWiringEdges = indexEdges(snapshot.edges(), false);
        this.incomingInjectionEdges = indexEdges(snapshot.edges(), true);
    }

    Snapshot snapshot() {
        return snapshot;
    }

    Resolution resolve(String query) {
        if (query == null || query.isBlank()) {
            return new Resolution.NotFound(query == null ? "" : query);
        }
        String normalized = query.strip();
        List<Node> matches;
        if (normalized.startsWith("b:") || normalized.startsWith("t:")) {
            Node exact = nodesById.get(normalized);
            matches = exact != null && (exact.kind() == NodeKind.BEAN || exact.kind() == NodeKind.TYPE)
                    ? List.of(exact) : List.of();
        } else {
            matches = snapshot.nodes().stream()
                    .filter(node -> node.kind() == NodeKind.BEAN || node.kind() == NodeKind.TYPE)
                    .filter(node -> names(node).contains(normalized))
                    .sorted(Comparator.comparing(node -> node.id().value()))
                    .toList();
        }
        if (matches.isEmpty()) {
            return new Resolution.NotFound(normalized);
        }
        if (matches.size() > 1) {
            return new Resolution.Ambiguous(normalized, matches);
        }
        return new Resolution.Found(normalized, matches.get(0));
    }

    List<Map<String, Object>> injectionChains(Node resolved) {
        List<Node> injectedBeans = switch (resolved.id()) {
            case BeanId ignored -> List.of(resolved);
            case TypeId type -> beansBoundTo(type);
            default -> List.of();
        };
        List<Map<String, Object>> chains = new ArrayList<>();
        for (Node injectedBean : injectedBeans) {
            for (Edge.Resolved injection : incomingInjectionEdges.getOrDefault(injectedBean.id(), List.of())) {
                Node injector = nodesById.get(injection.from().value());
                if (injector == null || injector.kind() != NodeKind.BEAN) {
                    continue;
                }
                Map<String, Object> chain = new LinkedHashMap<>();
                chain.put("injector", nodeReference(injector));
                chain.put("injected", nodeReference(injectedBean));
                chain.put("xmlParentInheritance", parentChain(injector).stream()
                        .map(SnapshotIndex::nodeReference).toList());
                chain.put("injectionEdge", edgeValue(injection));
                chain.put("injectorBindings", bindingValues(injector.id()));
                chain.put("injectedBindings", bindingValues(injectedBean.id()));
                chains.add(Map.copyOf(chain));
            }
        }
        chains.sort(Comparator.comparing(chain -> ((Map<?, ?>) chain.get("injectionEdge")).get("id").toString()));
        return List.copyOf(chains);
    }

    static Map<String, Object> nodeReference(Node node) {
        return Map.of(
                "id", node.id().value(),
                "kind", node.kind().name(),
                "name", node.name());
    }

    static Map<String, Object> edgeValue(Edge.Resolved edge) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", edge.id().value());
        value.put("kind", edge.kind().name());
        value.put("from", edge.from().value());
        value.put("to", edge.to().value());
        edge.site().ifPresent(site -> value.put("site", site.value()));
        value.put("provenance", edge.provenance().stream().sorted(Comparator.comparingInt(Enum::ordinal))
                .map(Enum::name).toList());
        value.put("confidence", edge.confidence());
        value.put("ruleIds", edge.rules());
        value.put("evidence", edge.evidence().stream().map(SnapshotIndex::evidenceValue).toList());
        value.put("dispatch", edge.dispatch().name());
        value.put("candidates", edge.candidates().stream().map(CanonicalId::value).toList());
        value.put("status", edge.status().name());
        return Map.copyOf(value);
    }

    private List<Map<String, Object>> bindingValues(CanonicalId bean) {
        return outgoingWiringEdges.getOrDefault(bean, List.of()).stream()
                .filter(edge -> edge.kind() == EdgeKind.BINDS)
                .map(SnapshotIndex::edgeValue)
                .toList();
    }

    private List<Node> beansBoundTo(TypeId type) {
        return snapshot.edges().stream()
                .filter(Edge.Resolved.class::isInstance)
                .map(Edge.Resolved.class::cast)
                .filter(edge -> edge.kind() == EdgeKind.BINDS && edge.to().equals(type))
                .map(edge -> nodesById.get(edge.from().value()))
                .filter(node -> node != null && node.kind() == NodeKind.BEAN)
                .distinct()
                .sorted(Comparator.comparing(node -> node.id().value()))
                .toList();
    }

    private List<Node> parentChain(Node bean) {
        List<Node> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Node current = bean;
        while (current != null && seen.add(current.id().value())) {
            chain.add(current);
            current = parent(current).map(nodesById::get).orElse(null);
        }
        return List.copyOf(chain);
    }

    private static Optional<String> parent(Node bean) {
        JsonValue parent = bean.attrs().get("parent");
        if (!(parent instanceof JsonValue.StringValue string) || string.value().isBlank()
                || !(bean.id() instanceof BeanId beanId)) {
            return Optional.empty();
        }
        String value = string.value();
        if (value.startsWith("b:")) {
            return Optional.of(value);
        }
        return Optional.of(new BeanId(beanId.context(), value).value());
    }

    private static Set<String> names(Node node) {
        Set<String> names = new LinkedHashSet<>();
        names.add(node.name());
        switch (node.id()) {
            case BeanId bean -> names.add(bean.beanId());
            case TypeId type -> {
                names.add(type.binaryName());
                names.add(simpleName(type.binaryName()));
            }
            default -> {
                // Only bean and type nodes reach this method.
            }
        }
        return Set.copyOf(names);
    }

    private static String simpleName(String binaryName) {
        int packageSeparator = binaryName.lastIndexOf('.');
        return binaryName.substring(packageSeparator + 1);
    }

    private static Map<CanonicalId, List<Edge.Resolved>> indexEdges(List<Edge> edges, boolean incomingInjections) {
        Map<CanonicalId, List<Edge.Resolved>> indexed = new LinkedHashMap<>();
        edges.stream()
                .filter(Edge.Resolved.class::isInstance)
                .map(Edge.Resolved.class::cast)
                .filter(edge -> edge.kind() == EdgeKind.BINDS || edge.kind() == EdgeKind.INJECTS)
                .filter(edge -> !incomingInjections || edge.kind() == EdgeKind.INJECTS)
                .sorted(Comparator.comparing(edge -> edge.id().value()))
                .forEach(edge -> {
                    CanonicalId key = incomingInjections ? edge.to() : edge.from();
                    indexed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(edge);
                });
        indexed.replaceAll((ignored, values) -> List.copyOf(values));
        return Map.copyOf(indexed);
    }

    private static Map<String, Object> evidenceValue(Evidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("layer", evidence.layer().name());
        switch (evidence) {
            case Evidence.Ast ast -> {
                value.put("file", ast.file());
                value.put("line", ast.line());
                value.put("text", ast.text());
            }
            case Evidence.Bytecode bytecode -> {
                value.put("class", bytecode.classFile());
                value.put("offset", bytecode.offset());
                value.put("instruction", bytecode.instruction());
                value.put("owner", bytecode.owner());
                value.put("name", bytecode.name());
                value.put("descriptor", bytecode.descriptor());
            }
            case Evidence.Wiring wiring -> {
                value.put("file", wiring.file());
                value.put("line", wiring.line());
                value.put("bean", wiring.bean().value());
            }
            case Evidence.Runtime runtime -> {
                value.put("runId", runtime.runId());
                value.put("hits", runtime.hits());
            }
        }
        return Map.copyOf(value);
    }

    sealed interface Resolution {
        String query();

        record Found(String query, Node node) implements Resolution {
        }

        record NotFound(String query) implements Resolution {
        }

        record Ambiguous(String query, List<Node> candidates) implements Resolution {
            public Ambiguous {
                candidates = List.copyOf(candidates);
            }
        }
    }
}
