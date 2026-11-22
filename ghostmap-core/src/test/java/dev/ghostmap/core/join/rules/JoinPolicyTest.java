package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.join.JoinedGraph;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.ProvisionalId;
import dev.ghostmap.core.layer.bytecode.BytecodeLayer;
import dev.ghostmap.core.layer.bytecode.TestCompiler;
import dev.ghostmap.core.layer.source.SourceLayer;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinPolicyTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void emptyAndSingleLayerFactsDoNotCrash() {
        JoinPolicy policy = new JoinPolicy();
        assertEquals(List.of("R01", "R02", "R03", "R04", "R05", "R07", "R09"), policy.ruleIds());
        assertEquals(new JoinedGraph(List.of(), List.of(), List.of(), List.of()), policy.join());

        TypeId type = new TypeId("sample.Ghost");
        JoinedGraph bytecodeOnly = policy.join(JoinTestSupport.facts(LayerId.BYTECODE,
                List.of(JoinTestSupport.typeNode(LayerId.BYTECODE, type, false)), List.of()));
        assertEquals(1, bytecodeOnly.nodes().size());
        assertTrue(bytecodeOnly.diagnostics().isEmpty());
    }

    @Test
    void astOnlyTargetResolutionKeepsSameArityOverloadsAmbiguous() {
        TypeId callerType = new TypeId("sample.Caller");
        TypeId targetType = new TypeId("sample.Target");
        MethodId caller = new MethodId(callerType.binaryName(), "call", "()V");
        MethodId integerOverload = new MethodId(targetType.binaryName(), "run", "(I)V");
        MethodId longOverload = new MethodId(targetType.binaryName(), "run", "(J)V");
        var call = JoinTestSupport.sourceCall(JoinTestSupport.canonical(caller), 12,
                "target", "run", 1, Optional.of(targetType));
        var uniqueFacts = JoinTestSupport.facts(LayerId.AST, List.of(
                JoinTestSupport.methodNode(LayerId.AST, caller, 10, 14, false, false),
                JoinTestSupport.methodNode(LayerId.AST, integerOverload, 20, 22, false, false)), List.of(call));
        var ambiguousFacts = JoinTestSupport.facts(LayerId.AST, List.of(
                JoinTestSupport.methodNode(LayerId.AST, caller, 10, 14, false, false),
                JoinTestSupport.methodNode(LayerId.AST, integerOverload, 20, 22, false, false),
                JoinTestSupport.methodNode(LayerId.AST, longOverload, 24, 26, false, false)), List.of(call));

        assertEquals(integerOverload, new JoinPolicy().join(uniqueFacts).edges().get(0).target().orElseThrow());
        assertTrue(new JoinPolicy().join(ambiguousFacts).edges().get(0).target().isEmpty());
    }

    @Test
    void reconcilesAnonymousOwnersAndGenericAbstractMethodsWithoutInventedStaleness() throws Exception {
        Path workspace = temporaryDirectory.resolve("identity-integration");
        Path classes = TestCompiler.compile(workspace, Map.of("sample.Owner", """
                package sample;
                interface Mapper<T> { T map(String value, int index); }
                abstract class Generic<K> { abstract K key(String value); }
                class Owner {
                    Mapper<Integer> mapper() {
                        return new Mapper<Integer>() {
                            @Override public Integer map(String value, int index) { return index; }
                        };
                    }
                }
                """), true);
        var source = new SourceLayer().analyse(new LayerInput(workspace.resolve("src")));
        var bytecode = new BytecodeLayer().analyse(LayerInput.forClassRoots(classes));

        JoinedGraph graph = new JoinPolicy().join(source, bytecode);

        String anonymousMethod = "m:sample.Owner$1#map(Ljava/lang/String;I)Ljava/lang/Integer;";
        String genericMethod = "m:sample.Generic#key(Ljava/lang/String;)Ljava/lang/Object;";
        assertTrue(graph.nodes().stream().anyMatch(node -> node.id().value().equals(anonymousMethod)
                && JoinTestSupport.strings(node.attrs().get("provenance")).containsAll(
                List.of("AST", "BYTECODE"))));
        assertTrue(graph.nodes().stream().anyMatch(node -> node.id().value().equals(genericMethod)
                && JoinTestSupport.strings(node.attrs().get("provenance")).containsAll(
                List.of("AST", "BYTECODE"))));
        assertTrue(graph.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.STALE_BUILD_MEMBER));
    }

    @Test
    void ambiguousProvisionalIdentityIsDiagnosedAndNotMerged() {
        TypeId owner = new TypeId("sample.Ghost");
        ProvisionalId provisional = new ProvisionalId(NodeKind.METHOD, owner, "haunt", 1, 10, 14);
        FactNode source = new FactNode(provisional, NodeKind.METHOD, "haunt", Optional.of(owner),
                Map.of("annotations", new ArrayValue(List.of()), "generated", new BooleanValue(false)),
                new SourceLocation("Ghost.java", 10, 14));
        MethodId integerMethod = new MethodId(owner.binaryName(), "haunt", "(I)V");
        MethodId longMethod = new MethodId(owner.binaryName(), "haunt", "(J)V");

        JoinedGraph graph = new JoinPolicy().join(
                JoinTestSupport.facts(LayerId.AST, List.of(source), List.of()),
                JoinTestSupport.facts(LayerId.BYTECODE, List.of(
                        JoinTestSupport.methodNode(LayerId.BYTECODE, integerMethod, 11, 13, false, false),
                        JoinTestSupport.methodNode(LayerId.BYTECODE, longMethod, 11, 13, false, false)), List.of()));

        assertEquals(List.of(source), graph.unresolvedNodes());
        assertEquals(2, graph.nodes().size());
        assertTrue(graph.nodes().stream().noneMatch(node ->
                JoinTestSupport.bool(node.attrs().get("generated"))));
        assertTrue(graph.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.AMBIGUOUS_IDENTITY));
    }
}
