package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.core.layer.source.SourceCallFact;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Confidence;
import dev.ghostmap.model.LayerId;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R02CallMatchProperties {
    @Property(tries = 250, generation = GenerationMode.RANDOMIZED)
    void exactTupleMatchIsDeterministicAndAddingBytecodeNeverLowersConfidence(
            @ForAll("methodNames") String methodName,
            @ForAll("exactInvocations") JvmInvocationKind invocation,
            @ForAll @IntRange(min = 0, max = 6) int arity,
            @ForAll @IntRange(min = 0, max = 5) int descriptorFlavor,
            @ForAll @IntRange(min = 2, max = 500) int line,
            @ForAll @IntRange(min = 0, max = 200) int ownerIndex,
            @ForAll boolean receiverResolved,
            @ForAll long shuffleSeed) {
        TypeId callerType = new TypeId("sample.Caller" + ownerIndex);
        TypeId targetType = new TypeId("sample.Target" + ownerIndex);
        MethodId caller = new MethodId(callerType.binaryName(), "call", "()V");
        MethodId target = new MethodId(targetType.binaryName(), methodName,
                JoinTestSupport.methodDescriptor(arity, descriptorFlavor));
        FactNode sourceCaller = JoinTestSupport.methodNode(LayerId.AST, caller, line - 1, line + 1,
                false, false);
        FactNode bytecodeCaller = JoinTestSupport.methodNode(LayerId.BYTECODE, caller, line - 1, line + 1,
                false, false);
        SourceCallFact sourceCall = JoinTestSupport.sourceCall(JoinTestSupport.canonical(caller), line,
                "target", methodName, arity, receiverResolved ? Optional.of(targetType) : Optional.empty());
        var ast = JoinTestSupport.facts(LayerId.AST, JoinTestSupport.shuffled(List.of(
                sourceCaller, JoinTestSupport.typeNode(LayerId.AST, callerType, false)), shuffleSeed),
                List.of(sourceCall));
        var bytes = JoinTestSupport.facts(LayerId.BYTECODE, JoinTestSupport.shuffled(List.of(
                        bytecodeCaller,
                        JoinTestSupport.typeNode(LayerId.BYTECODE, callerType, false),
                        JoinTestSupport.typeNode(LayerId.BYTECODE, targetType, false),
                        JoinTestSupport.methodNode(LayerId.BYTECODE, target, 1, 1, false, false)), ~shuffleSeed),
                List.of(JoinTestSupport.bytecodeCall(caller, target, Math.floorMod((int) shuffleSeed, 200) + 1,
                        OptionalInt.of(line), invocation)));

        var graph = (shuffleSeed & 1L) == 0 ? new JoinPolicy().join(ast, bytes)
                : new JoinPolicy().join(bytes, ast);
        var opposite = (shuffleSeed & 1L) == 0 ? new JoinPolicy().join(bytes, ast)
                : new JoinPolicy().join(ast, bytes);
        double astOnlyConfidence = new JoinPolicy().join(ast).edges().get(0).confidence();
        JoinedEdge edge = graph.edges().get(0);

        assertEquals(opposite, graph);
        assertEquals(Set.of(LayerId.AST, LayerId.BYTECODE), edge.provenance());
        assertEquals(2, edge.evidence().size());
        assertEquals(1, graph.modelEdges().size());
        assertTrue(edge.rules().contains("R02"));
        double astWeight = receiverResolved ? R02CallMatch.AST_RECEIVER_RESOLVED_WEIGHT
                : R02CallMatch.AST_NAME_ONLY_WEIGHT;
        assertEquals(Confidence.noisyOr(astWeight, R02CallMatch.BYTECODE_EXACT_WEIGHT), edge.confidence());
        assertTrue(edge.confidence() >= astOnlyConfidence);
        assertTrue(edge.confidence() >= 0.0 && edge.confidence() < 1.0);
    }

    @Property(tries = 250, generation = GenerationMode.RANDOMIZED)
    void mismatchedTupleLeavesSourceCallAstOnly(
            @ForAll("methodNames") String methodName,
            @ForAll @IntRange(min = 0, max = 6) int arity,
            @ForAll @IntRange(min = 0, max = 5) int descriptorFlavor,
            @ForAll @IntRange(min = 2, max = 500) int line,
            @ForAll @IntRange(min = 0, max = 200) int ownerIndex,
            @ForAll boolean receiverResolved,
            @ForAll long shuffleSeed) {
        TypeId callerType = new TypeId("sample.Caller" + ownerIndex);
        TypeId targetType = new TypeId("sample.Target" + ownerIndex);
        MethodId caller = new MethodId(callerType.binaryName(), "call", "()V");
        MethodId otherTarget = new MethodId(targetType.binaryName(), methodName + "x",
                JoinTestSupport.methodDescriptor(arity, descriptorFlavor));
        SourceCallFact sourceCall = JoinTestSupport.sourceCall(JoinTestSupport.canonical(caller), line,
                "target", methodName, arity, receiverResolved ? Optional.of(targetType) : Optional.empty());
        var ast = JoinTestSupport.facts(LayerId.AST,
                List.of(JoinTestSupport.methodNode(LayerId.AST, caller, line - 1, line + 1, false, false)),
                List.of(sourceCall));
        var bytes = JoinTestSupport.facts(LayerId.BYTECODE, List.of(
                        JoinTestSupport.methodNode(LayerId.BYTECODE, caller, line - 1, line + 1, false, false),
                        JoinTestSupport.methodNode(LayerId.BYTECODE, otherTarget, 1, 1, false, false)),
                List.of(JoinTestSupport.bytecodeCall(caller, otherTarget, 3, OptionalInt.of(line),
                        JvmInvocationKind.INVOKESTATIC)));

        var graph = (shuffleSeed & 1L) == 0 ? new JoinPolicy().join(ast, bytes)
                : new JoinPolicy().join(bytes, ast);
        JoinedEdge edge = graph.edges().stream().filter(candidate -> candidate.sourceFact().isPresent())
                .findFirst().orElseThrow();

        assertEquals(Set.of(LayerId.AST), edge.provenance());
        assertTrue(edge.target().isEmpty());
        assertEquals(receiverResolved ? 0.55 : 0.30, edge.confidence());
    }

    @Provide
    Arbitrary<String> methodNames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }

    @Provide
    Arbitrary<JvmInvocationKind> exactInvocations() {
        return Arbitraries.of(JvmInvocationKind.INVOKESTATIC, JvmInvocationKind.INVOKESPECIAL);
    }
}
