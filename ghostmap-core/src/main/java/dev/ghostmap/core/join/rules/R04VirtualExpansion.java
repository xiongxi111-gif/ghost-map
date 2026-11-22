package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.EvidenceContribution;
import dev.ghostmap.core.join.JoinContext;
import dev.ghostmap.core.join.JoinRule;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.core.layer.bytecode.BytecodeHierarchyFact;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** R04: expand virtual and interface invokes to concrete implementations in scope. */
public final class R04VirtualExpansion implements JoinRule {
    public static final String ID = "R04";
    public static final double VIRTUAL_WEIGHT = 0.85;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(JoinContext context) {
        Map<String, Set<String>> parents = hierarchy(context);
        List<MethodNode> methods = context.nodes().stream()
                .filter(node -> node.id() instanceof MethodId)
                .map(node -> new MethodNode((MethodId) node.id(), node))
                .filter(MethodNode::concrete)
                .toList();
        List<JoinedEdge> expanded = new ArrayList<>();
        for (JoinedEdge edge : context.edges()) {
            if (edge.invocationKind().filter(kind -> kind == JvmInvocationKind.INVOKEVIRTUAL
                    || kind == JvmInvocationKind.INVOKEINTERFACE).isEmpty()
                    || edge.target().filter(MethodId.class::isInstance).isEmpty()) {
                expanded.add(edge);
                continue;
            }
            MethodId declaredTarget = (MethodId) edge.target().orElseThrow();
            List<CanonicalId> candidates = methods.stream()
                    .filter(method -> method.id().name().equals(declaredTarget.name()))
                    .filter(method -> method.id().descriptor().equals(declaredTarget.descriptor()))
                    .filter(method -> isSubtype(method.id().binaryOwner(), declaredTarget.binaryOwner(), parents))
                    .map(MethodNode::id)
                    .map(id -> (CanonicalId) id)
                    .distinct()
                    .sorted(Comparator.comparing(CanonicalId::value))
                    .toList();
            if (candidates.isEmpty()) {
                List<CanonicalId> declared = List.of(declaredTarget);
                expanded.add(edge.withTarget(declaredTarget, declared,
                        splitContribution(edge.contributions(), 1), EdgeStatus.CANDIDATE).withRule(ID));
                continue;
            }
            int candidateCount = candidates.size();
            List<EvidenceContribution> contributions = splitContribution(edge.contributions(), candidateCount);
            for (CanonicalId candidate : candidates) {
                expanded.add(edge.withTarget(candidate, candidates, contributions, EdgeStatus.CANDIDATE)
                        .withRule(ID));
            }
        }
        context.setEdges(expanded);
    }

    private static Map<String, Set<String>> hierarchy(JoinContext context) {
        Map<String, Set<String>> parents = new HashMap<>();
        context.edgeFacts().stream().filter(entry -> entry.fact() instanceof BytecodeHierarchyFact)
                .map(entry -> (BytecodeHierarchyFact) entry.fact()).forEach(edge -> {
                    String child = ((TypeId) ((dev.ghostmap.core.layer.SymbolId.Canonical) edge.from()).id())
                            .binaryName();
                    String parent = ((TypeId) ((dev.ghostmap.core.layer.SymbolId.Canonical) edge.target()).id())
                            .binaryName();
                    parents.computeIfAbsent(child, ignored -> new LinkedHashSet<>()).add(parent);
                });
        return parents;
    }

    private static boolean isSubtype(String candidate, String declared, Map<String, Set<String>> parents) {
        if (candidate.equals(declared)) {
            return true;
        }
        Set<String> visited = new HashSet<>();
        List<String> pending = new ArrayList<>();
        pending.add(candidate);
        while (!pending.isEmpty()) {
            String current = pending.remove(pending.size() - 1);
            if (!visited.add(current)) {
                continue;
            }
            for (String parent : parents.getOrDefault(current, Set.of())) {
                if (parent.equals(declared)) {
                    return true;
                }
                pending.add(parent);
            }
        }
        return false;
    }

    private static List<EvidenceContribution> splitContribution(List<EvidenceContribution> contributions, int count) {
        return contributions.stream().map(contribution -> contribution.layer() == LayerId.BYTECODE
                ? new EvidenceContribution(LayerId.BYTECODE, VIRTUAL_WEIGHT / count,
                "BYTECODE_VIRTUAL_CANDIDATE_1_OF_" + count)
                : contribution).toList();
    }

    private record MethodNode(MethodId id, Node node) {
        boolean concrete() {
            return !(node.attrs().get("abstract") instanceof BooleanValue value && value.value());
        }
    }
}
