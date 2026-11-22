package dev.ghostmap.cli;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.join.JoinedGraph;
import dev.ghostmap.model.BlindSpotReport;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.CodeVersion;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.ExercisedCoverage;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.Node;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.Snapshot;
import dev.ghostmap.model.SnapshotJson;
import dev.ghostmap.model.StaticRecall;
import dev.ghostmap.model.ToolInfo;
import dev.ghostmap.model.UnresolvedSiteKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

final class SnapshotAssembler {
    private static final String ZERO_HASH = "sha256:" + "0".repeat(64);

    Snapshot assemble(JoinedGraph graph, CodeVersion codeVersion, Set<LayerId> layersRun) {
        List<Edge> edges = distinctEdges(graph.modelEdges());
        List<Node> nodes = closeNodeSet(graph.nodes(), edges);
        int unmaterialized = graph.edges().size() - graph.modelEdges().size();
        BlindSpotReport report = report(nodes, edges, unmaterialized);
        Snapshot placeholder = new Snapshot(Snapshot.SCHEMA, ZERO_HASH, codeVersion,
                new ToolInfo("ghostmap", "0.1.0", JoinPolicy.VERSION), layersRun, List.of(), nodes, edges, report);
        String snapshotId = hash(SnapshotJson.toJson(placeholder));
        return new Snapshot(Snapshot.SCHEMA, snapshotId, codeVersion, placeholder.tool(), layersRun,
                List.of(), nodes, edges, report);
    }

    private static List<Edge> distinctEdges(List<Edge> edges) {
        Map<String, Edge> distinct = new LinkedHashMap<>();
        edges.stream().sorted(Comparator.comparing(edge -> edge.id().value()))
                .forEach(edge -> distinct.putIfAbsent(edge.id().value(), edge));
        return List.copyOf(distinct.values());
    }

    private static List<Node> closeNodeSet(List<Node> joinedNodes, List<Edge> edges) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        joinedNodes.forEach(node -> nodes.put(node.id().value(), node));
        for (Edge edge : edges) {
            addInferredNode(nodes, edge.from());
            edge.target().ifPresent(target -> addInferredNode(nodes, target));
            edge.candidates().forEach(candidate -> addInferredNode(nodes, candidate));
        }
        return nodes.values().stream().sorted(Comparator.comparing(node -> node.id().value())).toList();
    }

    private static void addInferredNode(Map<String, Node> nodes, CanonicalId id) {
        if (nodes.containsKey(id.value())) {
            return;
        }
        Optional<TypeId> owner = id instanceof MethodId method
                ? Optional.of(new TypeId(method.binaryOwner())) : Optional.empty();
        String name = id instanceof MethodId method ? method.name() : id.value();
        Map<String, JsonValue> attrs = Map.of(
                "annotations", new ArrayValue(List.of()),
                "generated", new BooleanValue(false),
                "inferredFromCall", new BooleanValue(true),
                "provenance", new ArrayValue(List.of(new StringValue("BYTECODE"))));
        nodes.put(id.value(), new Node(id, id.kind(), name, owner, attrs, Optional.empty()));
        owner.ifPresent(type -> nodes.putIfAbsent(type.value(), new Node(type, NodeKind.TYPE,
                simpleName(type), Optional.empty(), attrs, Optional.empty())));
    }

    private static String simpleName(TypeId type) {
        int separator = Math.max(type.binaryName().lastIndexOf('.'), type.binaryName().lastIndexOf('$'));
        return type.binaryName().substring(separator + 1);
    }

    private static BlindSpotReport report(List<Node> nodes, List<Edge> edges, int unmaterialized) {
        Map<String, Long> byProvenance = new LinkedHashMap<>();
        for (Edge edge : edges) {
            String key = edge.provenance().stream().sorted(Comparator.comparingInt(Enum::ordinal))
                    .map(Enum::name).reduce((left, right) -> left + '+' + right).orElse("NONE");
            byProvenance.merge(key, 1L, Long::sum);
        }
        Map<UnresolvedSiteKind, Long> unresolved = new EnumMap<>(UnresolvedSiteKind.class);
        long methods = nodes.stream().filter(node -> node.kind() == NodeKind.METHOD).count();
        String caveat = "L1/L2 snapshot only. Runtime coverage, recall, wiring, and R07 unresolved-site "
                + "classification are not computed. " + unmaterialized
                + " source call fact(s) lacked canonical endpoints and remain outside snapshot edges.";
        return new BlindSpotReport(byProvenance, unresolved, 0,
                new ExercisedCoverage(0, methods),
                new StaticRecall(OptionalDouble.empty(), OptionalDouble.empty()), caveat);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }
}
