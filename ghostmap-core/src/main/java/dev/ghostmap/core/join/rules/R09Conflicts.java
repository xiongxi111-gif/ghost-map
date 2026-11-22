package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.EvidenceContribution;
import dev.ghostmap.core.join.JoinContext;
import dev.ghostmap.core.join.JoinRule;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.DiagnosticSeverity;
import dev.ghostmap.core.layer.bytecode.BytecodeCallFact;
import dev.ghostmap.core.layer.source.SourceCallFact;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.Confidence;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** R09: expose stale source/bytecode conflicts and penalize AST weights before noisy-OR. */
public final class R09Conflicts implements JoinRule {
    public static final String ID = "R09";
    public static final double STALE_AST_MULTIPLIER = 0.5;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(JoinContext context) {
        if (!context.hasLayer(LayerId.AST) || !context.hasLayer(LayerId.BYTECODE)) {
            return;
        }
        Set<CanonicalId> bytecodeIds = new HashSet<>();
        context.nodeFacts().stream().filter(fact -> fact.layer() == LayerId.BYTECODE)
                .forEach(fact -> context.resolve(fact.node().id()).ifPresent(bytecodeIds::add));
        Set<CanonicalId> missingMembers = new HashSet<>();
        Set<String> diagnosed = new HashSet<>();
        for (JoinContext.NodeFact fact : context.nodeFacts()) {
            if (fact.layer() != LayerId.AST || (fact.node().kind() != NodeKind.METHOD
                    && fact.node().kind() != NodeKind.FIELD)) {
                continue;
            }
            Optional<CanonicalId> resolved = context.resolve(fact.node().id());
            boolean missing = resolved.isEmpty() || resolved.filter(bytecodeIds::contains).isEmpty();
            if (!missing) {
                continue;
            }
            resolved.ifPresent(missingMembers::add);
            if (diagnosed.add(fact.node().id().value())) {
                SourceLocation location = fact.node().location();
                context.addDiagnostic(new Diagnostic(DiagnosticCode.STALE_BUILD_MEMBER,
                        DiagnosticSeverity.WARNING, location.file(), location.line(),
                        "source member has no bytecode counterpart: " + fact.node().id().value()));
            }
        }
        markMissingNodes(context, missingMembers);

        List<BytecodeCallFact> bytecodeCalls = context.edgeFacts().stream()
                .filter(entry -> entry.fact() instanceof BytecodeCallFact)
                .map(entry -> (BytecodeCallFact) entry.fact())
                .filter(call -> call.sourceLine().isPresent())
                .sorted(Comparator.comparingInt(call -> call.site().bytecodeOffset()))
                .toList();
        List<JoinedEdge> updated = new ArrayList<>();
        for (JoinedEdge edge : context.edges()) {
            boolean staleMember = context.resolve(edge.from()).filter(missingMembers::contains).isPresent();
            Optional<LineDrift> drift = edge.sourceFact().filter(ignored -> edge.bytecodeFact().isEmpty())
                    .flatMap(source -> lineDrift(context, source, bytecodeCalls));
            if (drift.isPresent()) {
                SourceCallFact source = edge.sourceFact().orElseThrow();
                LineDrift conflict = drift.orElseThrow();
                context.addDiagnostic(new Diagnostic(DiagnosticCode.STALE_BUILD_LINE_DRIFT,
                        DiagnosticSeverity.WARNING, source.evidence().file(), source.evidence().line(),
                        "source call line " + source.evidence().line() + " differs from bytecode line "
                                + conflict.bytecodeLine() + " for " + source.methodName()));
            }
            if (staleMember || drift.isPresent()) {
                List<EvidenceContribution> penalized = edge.contributions().stream()
                        .map(contribution -> contribution.layer() == LayerId.AST
                                ? new EvidenceContribution(LayerId.AST,
                                Confidence.applyPenalty(contribution.weight(), STALE_AST_MULTIPLIER),
                                contribution.reason() + "_STALE_X_0_5")
                                : contribution)
                        .toList();
                updated.add(edge.withContributions(penalized, true).withRule(ID));
            } else {
                updated.add(edge);
            }
        }
        context.setEdges(updated);
    }

    private static Optional<LineDrift> lineDrift(JoinContext context, SourceCallFact source,
                                                 List<BytecodeCallFact> bytecodeCalls) {
        String caller = context.resolve(source.from()).map(CanonicalId::value).orElse(source.from().value());
        return bytecodeCalls.stream().filter(bytecode -> {
            String bytecodeCaller = context.resolve(bytecode.from()).map(CanonicalId::value)
                    .orElse(bytecode.from().value());
            return caller.equals(bytecodeCaller)
                    && source.methodName().equals(bytecode.methodName())
                    && source.arity() == Type.getArgumentTypes(bytecode.descriptor()).length
                    && source.evidence().line() != bytecode.sourceLine().orElseThrow();
        }).map(bytecode -> new LineDrift(bytecode.sourceLine().orElseThrow())).findFirst();
    }

    private static void markMissingNodes(JoinContext context, Set<CanonicalId> missingMembers) {
        List<Node> nodes = context.nodes().stream().map(node -> {
            if (!missingMembers.contains(node.id())) {
                return node;
            }
            Map<String, JsonValue> attrs = new LinkedHashMap<>(node.attrs());
            attrs.put("staleBuild", new BooleanValue(true));
            Set<String> rules = new LinkedHashSet<>(strings(node.attrs().get("joinRules")));
            rules.add(ID);
            attrs.put("joinRules", new ArrayValue(rules.stream().sorted()
                    .map(value -> (JsonValue) new StringValue(value)).toList()));
            return new Node(node.id(), node.kind(), node.name(), node.owner(), attrs, node.location());
        }).toList();
        context.setNodes(nodes);
    }

    private static List<String> strings(JsonValue value) {
        if (!(value instanceof ArrayValue array)) {
            return List.of();
        }
        return array.values().stream().filter(StringValue.class::isInstance).map(StringValue.class::cast)
                .map(StringValue::value).toList();
    }

    private record LineDrift(int bytecodeLine) {
    }
}
