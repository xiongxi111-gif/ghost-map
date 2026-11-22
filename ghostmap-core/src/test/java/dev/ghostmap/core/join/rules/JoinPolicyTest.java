package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.join.JoinedGraph;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.ProvisionalId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinPolicyTest {
    @Test
    void emptyAndSingleLayerFactsDoNotCrash() {
        JoinPolicy policy = new JoinPolicy();
        assertEquals(List.of("R01", "R02", "R03", "R04", "R09"), policy.ruleIds());
        assertEquals(new JoinedGraph(List.of(), List.of(), List.of(), List.of()), policy.join());

        TypeId type = new TypeId("sample.Ghost");
        JoinedGraph bytecodeOnly = policy.join(JoinTestSupport.facts(LayerId.BYTECODE,
                List.of(JoinTestSupport.typeNode(LayerId.BYTECODE, type, false)), List.of()));
        assertEquals(1, bytecodeOnly.nodes().size());
        assertTrue(bytecodeOnly.diagnostics().isEmpty());
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
