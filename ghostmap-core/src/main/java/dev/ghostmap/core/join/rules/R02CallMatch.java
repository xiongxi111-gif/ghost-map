package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.EvidenceContribution;
import dev.ghostmap.core.join.JoinContext;
import dev.ghostmap.core.join.JoinRule;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.core.layer.bytecode.BytecodeCallFact;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.core.layer.source.SourceCallFact;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.LayerId;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** R02: match source calls to bytecode invokes by caller, line, name, and arity. */
public final class R02CallMatch implements JoinRule {
    public static final String ID = "R02";
    public static final double AST_NAME_ONLY_WEIGHT = 0.30;
    public static final double AST_RECEIVER_RESOLVED_WEIGHT = 0.55;
    public static final double BYTECODE_EXACT_WEIGHT = 0.90;
    public static final double BYTECODE_VIRTUAL_WEIGHT = 0.85;
    public static final double BYTECODE_DYNAMIC_WEIGHT = 0.30;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(JoinContext context) {
        List<SourceCallFact> sources = context.edgeFacts().stream()
                .filter(entry -> entry.fact() instanceof SourceCallFact)
                .map(entry -> (SourceCallFact) entry.fact()).toList();
        List<BytecodeCallFact> bytecodes = context.edgeFacts().stream()
                .filter(entry -> entry.fact() instanceof BytecodeCallFact)
                .map(entry -> (BytecodeCallFact) entry.fact()).toList();

        Map<MatchKey, List<SourceCallFact>> sourceGroups = new LinkedHashMap<>();
        for (SourceCallFact source : sources) {
            sourceGroups.computeIfAbsent(sourceKey(context, source), ignored -> new ArrayList<>()).add(source);
        }
        Map<MatchKey, List<BytecodeCallFact>> bytecodeGroups = new LinkedHashMap<>();
        List<BytecodeCallFact> withoutLines = new ArrayList<>();
        for (BytecodeCallFact bytecode : bytecodes) {
            if (bytecode.sourceLine().isEmpty()) {
                withoutLines.add(bytecode);
            } else {
                bytecodeGroups.computeIfAbsent(bytecodeKey(context, bytecode), ignored -> new ArrayList<>())
                        .add(bytecode);
            }
        }

        Set<MatchKey> allKeys = new LinkedHashSet<>();
        allKeys.addAll(sourceGroups.keySet());
        allKeys.addAll(bytecodeGroups.keySet());
        List<JoinedEdge> joined = new ArrayList<>();
        allKeys.stream().sorted().forEach(key -> {
            List<SourceCallFact> matchingSources = sourceGroups.getOrDefault(key, List.of()).stream()
                    .sorted(Comparator.comparingInt(source -> source.site().column())).toList();
            List<BytecodeCallFact> matchingBytecodes = bytecodeGroups.getOrDefault(key, List.of()).stream()
                    .sorted(Comparator.comparingInt(bytecode -> bytecode.site().bytecodeOffset())).toList();
            int pairs = Math.min(matchingSources.size(), matchingBytecodes.size());
            for (int index = 0; index < pairs; index++) {
                joined.add(edge(context, Optional.of(matchingSources.get(index)),
                        Optional.of(matchingBytecodes.get(index))));
            }
            matchingSources.subList(pairs, matchingSources.size()).forEach(source ->
                    joined.add(edge(context, Optional.of(source), Optional.empty())));
            matchingBytecodes.subList(pairs, matchingBytecodes.size()).forEach(bytecode ->
                    joined.add(edge(context, Optional.empty(), Optional.of(bytecode))));
        });
        withoutLines.stream().sorted(Comparator.comparingInt(call -> call.site().bytecodeOffset()))
                .forEach(bytecode -> joined.add(edge(context, Optional.empty(), Optional.of(bytecode))));
        context.setEdges(joined);
    }

    private static JoinedEdge edge(JoinContext context, Optional<SourceCallFact> source,
                                   Optional<BytecodeCallFact> bytecode) {
        SymbolId from = source.<SymbolId>map(fact -> context.resolve(fact.from())
                        .<SymbolId>map(SymbolId.Canonical::new).orElse(fact.from()))
                .orElseGet(() -> bytecode.orElseThrow().from());
        Optional<CanonicalId> target = bytecode.flatMap(BytecodeCallFact::target)
                .filter(SymbolId.Canonical.class::isInstance).map(SymbolId.Canonical.class::cast)
                .map(SymbolId.Canonical::id)
                .or(() -> source.flatMap(fact -> sourceTarget(context, fact)));
        List<EvidenceContribution> contributions = new ArrayList<>(2);
        source.ifPresent(fact -> contributions.add(new EvidenceContribution(LayerId.AST,
                fact.receiverType().isPresent() ? AST_RECEIVER_RESOLVED_WEIGHT : AST_NAME_ONLY_WEIGHT,
                fact.receiverType().isPresent() ? "AST_RECEIVER_RESOLVED" : "AST_NAME_ONLY")));
        bytecode.ifPresent(fact -> contributions.add(new EvidenceContribution(LayerId.BYTECODE,
                bytecodeWeight(fact.invocationKind()), "BYTECODE_" + fact.invocationKind().name())));
        DispatchKind dispatch = bytecode.map(BytecodeCallFact::dispatch)
                .orElseGet(() -> source.map(SourceCallFact::dispatch).orElse(DispatchKind.DYNAMIC));
        EdgeStatus status = bytecode.map(fact -> switch (fact.invocationKind()) {
            case INVOKESTATIC, INVOKESPECIAL -> EdgeStatus.PREDICTED;
            case INVOKEVIRTUAL, INVOKEINTERFACE, INVOKEDYNAMIC -> EdgeStatus.CANDIDATE;
        }).orElse(EdgeStatus.CANDIDATE);
        List<CanonicalId> candidates = status == EdgeStatus.CANDIDATE ? target.stream().toList() : List.of();
        List<String> rules = new ArrayList<>();
        if (source.filter(fact -> fact.from().provisional() && context.resolve(fact.from()).isPresent()).isPresent()) {
            rules.add(R01Identity.ID);
        }
        rules.add(ID);
        return new JoinedEdge(from, target, bytecode.map(BytecodeCallFact::site), source, bytecode,
                contributions, rules, dispatch, status, candidates, false);
    }

    private static Optional<CanonicalId> sourceTarget(JoinContext context, SourceCallFact call) {
        if (call.receiverType().isEmpty()) {
            return Optional.empty();
        }
        String owner = call.receiverType().orElseThrow().binaryName();
        List<CanonicalId> candidates = context.nodes().stream()
                .map(node -> node.id())
                .filter(MethodId.class::isInstance)
                .map(MethodId.class::cast)
                .filter(method -> method.binaryOwner().equals(owner))
                .filter(method -> method.name().equals(call.methodName()))
                .filter(method -> Type.getArgumentTypes(method.descriptor()).length == call.arity())
                .map(method -> (CanonicalId) method)
                .distinct()
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    private static double bytecodeWeight(JvmInvocationKind kind) {
        return switch (kind) {
            case INVOKESTATIC, INVOKESPECIAL -> BYTECODE_EXACT_WEIGHT;
            case INVOKEVIRTUAL, INVOKEINTERFACE -> BYTECODE_VIRTUAL_WEIGHT;
            case INVOKEDYNAMIC -> BYTECODE_DYNAMIC_WEIGHT;
        };
    }

    private static MatchKey sourceKey(JoinContext context, SourceCallFact call) {
        String caller = context.resolve(call.from()).map(CanonicalId::value).orElse(call.from().value());
        return new MatchKey(caller, call.evidence().line(), call.methodName(), call.arity());
    }

    private static MatchKey bytecodeKey(JoinContext context, BytecodeCallFact call) {
        String caller = context.resolve(call.from()).map(CanonicalId::value).orElse(call.from().value());
        return new MatchKey(caller, call.sourceLine().orElseThrow(), call.methodName(),
                Type.getArgumentTypes(call.descriptor()).length);
    }

    private record MatchKey(String caller, int line, String methodName, int arity)
            implements Comparable<MatchKey> {
        @Override
        public int compareTo(MatchKey other) {
            int comparison = caller.compareTo(other.caller);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(line, other.line);
            if (comparison != 0) {
                return comparison;
            }
            comparison = methodName.compareTo(other.methodName);
            return comparison != 0 ? comparison : Integer.compare(arity, other.arity);
        }
    }
}
