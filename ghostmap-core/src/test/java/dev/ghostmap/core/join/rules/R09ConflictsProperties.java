package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.core.layer.source.SourceCallFact;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.LayerId;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R09ConflictsProperties {
    @Property(tries = 250, generation = GenerationMode.RANDOMIZED)
    void bytecodeMemberPresenceControlsPreCombinationAstPenalty(
            @ForAll("methodNames") String memberName,
            @ForAll @IntRange(min = 0, max = 6) int callArity,
            @ForAll @IntRange(min = 0, max = 5) int descriptorFlavor,
            @ForAll @IntRange(min = 2, max = 500) int line,
            @ForAll @IntRange(min = 0, max = 200) int ownerIndex,
            @ForAll boolean receiverResolved,
            @ForAll boolean bytecodeMemberPresent,
            @ForAll long shuffleSeed) {
        TypeId callerType = new TypeId("sample.Caller" + ownerIndex);
        MethodId sourceMember = new MethodId(callerType.binaryName(), memberName, "()V");
        SourceCallFact sourceCall = JoinTestSupport.sourceCall(JoinTestSupport.canonical(sourceMember), line,
                "target", "run", callArity,
                receiverResolved ? Optional.of(new TypeId("sample.Target" + ownerIndex)) : Optional.empty());
        var ast = JoinTestSupport.facts(LayerId.AST,
                List.of(JoinTestSupport.methodNode(LayerId.AST, sourceMember, line - 1, line + 1,
                        false, false)), List.of(sourceCall));
        List<FactNode> bytecodeNodes = new ArrayList<>();
        bytecodeNodes.add(JoinTestSupport.typeNode(LayerId.BYTECODE,
                new TypeId("sample.Unrelated" + descriptorFlavor), false));
        if (bytecodeMemberPresent) {
            bytecodeNodes.add(JoinTestSupport.methodNode(LayerId.BYTECODE, sourceMember,
                    line - 1, line + 1, false, false));
        }
        var bytes = JoinTestSupport.facts(LayerId.BYTECODE,
                JoinTestSupport.shuffled(bytecodeNodes, shuffleSeed), List.of());

        var graph = (shuffleSeed & 1L) == 0 ? new JoinPolicy().join(ast, bytes)
                : new JoinPolicy().join(bytes, ast);
        JoinedEdge edge = graph.edges().get(0);
        double base = receiverResolved ? 0.55 : 0.30;
        double expected = bytecodeMemberPresent ? base : base * R09Conflicts.STALE_AST_MULTIPLIER;

        assertEquals(expected, edge.confidence());
        assertEquals(!bytecodeMemberPresent, edge.staleBuild());
        assertEquals(!bytecodeMemberPresent, edge.rules().contains("R09"));
        assertEquals(!bytecodeMemberPresent, graph.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.STALE_BUILD_MEMBER));
        assertTrue(edge.confidence() >= 0.0 && edge.confidence() < 1.0);
    }

    @Property(tries = 250, generation = GenerationMode.RANDOMIZED)
    void lineDriftDistanceAndInputOrderDoNotChangePenaltySemantics(
            @ForAll("methodNames") String methodName,
            @ForAll @IntRange(min = 0, max = 6) int arity,
            @ForAll @IntRange(min = 0, max = 5) int descriptorFlavor,
            @ForAll @IntRange(min = 2, max = 500) int sourceLine,
            @ForAll @IntRange(min = 1, max = 100) int drift,
            @ForAll @IntRange(min = 0, max = 200) int ownerIndex,
            @ForAll boolean receiverResolved,
            @ForAll long shuffleSeed) {
        TypeId callerType = new TypeId("sample.Caller" + ownerIndex);
        TypeId targetType = new TypeId("sample.Target" + ownerIndex);
        MethodId caller = new MethodId(callerType.binaryName(), "call", "()V");
        MethodId target = new MethodId(targetType.binaryName(), methodName,
                JoinTestSupport.methodDescriptor(arity, descriptorFlavor));
        SourceCallFact sourceCall = JoinTestSupport.sourceCall(JoinTestSupport.canonical(caller), sourceLine,
                "target", methodName, arity, receiverResolved ? Optional.of(targetType) : Optional.empty());
        var ast = JoinTestSupport.facts(LayerId.AST,
                List.of(JoinTestSupport.methodNode(LayerId.AST, caller, sourceLine - 1, sourceLine + 1,
                        false, false)), List.of(sourceCall));
        var bytes = JoinTestSupport.facts(LayerId.BYTECODE, JoinTestSupport.shuffled(List.of(
                        JoinTestSupport.methodNode(LayerId.BYTECODE, caller, sourceLine - 1, sourceLine + 1,
                                false, false),
                        JoinTestSupport.methodNode(LayerId.BYTECODE, target, sourceLine + drift,
                                sourceLine + drift, false, false)), shuffleSeed),
                List.of(JoinTestSupport.bytecodeCall(caller, target, Math.floorMod((int) shuffleSeed, 200) + 1,
                        OptionalInt.of(sourceLine + drift), JvmInvocationKind.INVOKESTATIC)));

        var graph = (shuffleSeed & 1L) == 0 ? new JoinPolicy().join(ast, bytes)
                : new JoinPolicy().join(bytes, ast);
        var opposite = (shuffleSeed & 1L) == 0 ? new JoinPolicy().join(bytes, ast)
                : new JoinPolicy().join(ast, bytes);
        JoinedEdge astEdge = graph.edges().stream().filter(edge -> edge.sourceFact().isPresent())
                .filter(edge -> edge.bytecodeFact().isEmpty()).findFirst().orElseThrow();
        JoinedEdge bytecodeEdge = graph.edges().stream().filter(edge -> edge.bytecodeFact().isPresent())
                .findFirst().orElseThrow();
        double astBase = receiverResolved ? 0.55 : 0.30;

        assertEquals(opposite, graph);
        assertEquals(astBase * R09Conflicts.STALE_AST_MULTIPLIER, astEdge.confidence());
        assertTrue(astEdge.staleBuild());
        assertEquals(0.90, bytecodeEdge.confidence());
        assertFalse(bytecodeEdge.staleBuild());
        assertTrue(graph.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.STALE_BUILD_LINE_DRIFT));
        assertTrue(astEdge.confidence() >= 0.0 && astEdge.confidence() < 1.0);
    }

    @Provide
    Arbitrary<String> methodNames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }
}
