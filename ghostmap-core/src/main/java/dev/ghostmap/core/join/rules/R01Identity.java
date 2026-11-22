package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinContext;
import dev.ghostmap.core.join.JoinRule;
import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.DiagnosticSeverity;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.ProvisionalId;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.Node;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** R01: canonical identity merge and unique provisional source reconciliation. */
public final class R01Identity implements JoinRule {
    public static final String ID = "R01";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(JoinContext context) {
        Map<CanonicalId, List<JoinContext.NodeFact>> merged = new LinkedHashMap<>();
        List<FactNode> unresolved = new ArrayList<>();
        List<JoinContext.NodeFact> bytecodeMethods = context.nodeFacts().stream()
                .filter(fact -> fact.layer() == LayerId.BYTECODE)
                .filter(fact -> fact.node().kind() == NodeKind.METHOD)
                .filter(fact -> fact.node().id() instanceof SymbolId.Canonical canonical
                        && canonical.id() instanceof MethodId)
                .toList();

        for (JoinContext.NodeFact fact : context.nodeFacts()) {
            FactNode node = fact.node();
            if (node.id() instanceof SymbolId.Canonical canonical) {
                context.mapIdentity(node.id(), canonical.id());
                merged.computeIfAbsent(canonical.id(), ignored -> new ArrayList<>()).add(fact);
                continue;
            }
            if (!(node.id() instanceof ProvisionalId provisional) || fact.layer() != LayerId.AST) {
                unresolved.add(node);
                continue;
            }
            List<JoinContext.NodeFact> candidates = candidates(provisional, bytecodeMethods);
            if (candidates.size() == 1) {
                SymbolId.Canonical canonical = (SymbolId.Canonical) candidates.get(0).node().id();
                context.mapIdentity(provisional, canonical.id());
                merged.computeIfAbsent(canonical.id(), ignored -> new ArrayList<>()).add(fact);
            } else {
                unresolved.add(node);
                if (candidates.size() > 1) {
                    candidates.stream().map(candidate -> (SymbolId.Canonical) candidate.node().id())
                            .map(SymbolId.Canonical::id).forEach(context::markPotentialSourceIdentity);
                    SourceLocation location = node.location();
                    String ids = candidates.stream().map(candidate -> candidate.node().id().value()).sorted()
                            .reduce((left, right) -> left + ", " + right).orElse("");
                    context.addDiagnostic(new Diagnostic(DiagnosticCode.AMBIGUOUS_IDENTITY,
                            DiagnosticSeverity.WARNING, location.file(), location.line(),
                            "provisional id " + provisional.value() + " matches multiple bytecode members: " + ids));
                }
            }
        }

        List<Node> nodes = merged.entrySet().stream()
                .map(entry -> merge(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(node -> node.id().value()))
                .toList();
        context.setNodes(nodes);
        context.setUnresolvedNodes(unresolved);
    }

    private static List<JoinContext.NodeFact> candidates(ProvisionalId provisional,
                                                         List<JoinContext.NodeFact> bytecodeMethods) {
        if (provisional.kind() != NodeKind.METHOD) {
            return List.of();
        }
        return bytecodeMethods.stream().filter(candidate -> {
            SymbolId.Canonical canonical = (SymbolId.Canonical) candidate.node().id();
            MethodId method = (MethodId) canonical.id();
            return method.binaryOwner().equals(provisional.owner().binaryName())
                    && method.name().equals(provisional.name())
                    && Type.getArgumentTypes(method.descriptor()).length == provisional.arity()
                    && rangesOverlap(provisional, candidate.node().sourceLocation());
        }).sorted(Comparator.comparing(candidate -> candidate.node().id().value())).toList();
    }

    private static boolean rangesOverlap(ProvisionalId provisional, Optional<SourceLocation> candidate) {
        return candidate.filter(location -> provisional.startLine() <= location.endLine()
                && location.line() <= provisional.endLine()).isPresent();
    }

    private static Node merge(CanonicalId id, List<JoinContext.NodeFact> facts) {
        List<JoinContext.NodeFact> ordered = facts.stream().sorted(Comparator
                .comparing((JoinContext.NodeFact fact) -> fact.layer().ordinal())
                .thenComparing(fact -> fact.node().id().value())).toList();
        FactNode preferred = ordered.stream().filter(fact -> fact.layer() == LayerId.AST)
                .map(JoinContext.NodeFact::node).findFirst().orElse(ordered.get(0).node());
        Optional<SourceLocation> location = ordered.stream().filter(fact -> fact.layer() == LayerId.AST)
                .map(JoinContext.NodeFact::node).map(FactNode::sourceLocation).flatMap(Optional::stream)
                .findFirst().or(() -> ordered.stream().map(JoinContext.NodeFact::node)
                        .map(FactNode::sourceLocation).flatMap(Optional::stream).findFirst());
        Map<String, JsonValue> attrs = mergeAttributes(ordered);
        return new Node(id, id.kind(), preferred.name(), preferred.owner(), attrs, location);
    }

    private static Map<String, JsonValue> mergeAttributes(List<JoinContext.NodeFact> facts) {
        Map<String, JsonValue> merged = new LinkedHashMap<>();
        Set<String> annotations = new LinkedHashSet<>();
        Set<String> generationReasons = new LinkedHashSet<>();
        Set<String> provenance = new LinkedHashSet<>();
        boolean generated = false;
        for (JoinContext.NodeFact fact : facts) {
            provenance.add(fact.layer().name());
            for (Map.Entry<String, JsonValue> attribute : fact.node().attrs().entrySet()) {
                switch (attribute.getKey()) {
                    case "annotations" -> annotations.addAll(strings(attribute.getValue()));
                    case "generationReasons" -> generationReasons.addAll(strings(attribute.getValue()));
                    case "generated" -> generated |= booleanValue(attribute.getValue());
                    default -> merged.put(attribute.getKey(), attribute.getValue());
                }
            }
        }
        merged.put("annotations", array(annotations));
        merged.put("generated", new BooleanValue(generated));
        merged.put("generationReasons", array(generationReasons));
        merged.put("joinRules", array(Set.of(ID)));
        merged.put("provenance", array(provenance));
        return Map.copyOf(merged);
    }

    private static List<String> strings(JsonValue value) {
        if (!(value instanceof ArrayValue array)) {
            return List.of();
        }
        return array.values().stream().filter(StringValue.class::isInstance).map(StringValue.class::cast)
                .map(StringValue::value).toList();
    }

    private static boolean booleanValue(JsonValue value) {
        return value instanceof BooleanValue bool && bool.value();
    }

    private static ArrayValue array(Set<String> values) {
        return new ArrayValue(values.stream().sorted().map(value -> (JsonValue) new StringValue(value)).toList());
    }
}
