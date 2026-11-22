package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.FieldId;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R03GeneratedMembersProperties {
    @Property(tries = 250, generation = GenerationMode.RANDOMIZED)
    void bytecodeOnlyMemberIsRetainedAndMarkedAcrossMemberShapesAndInputOrder(
            @ForAll("memberNames") String memberName,
            @ForAll @IntRange(min = 0, max = 100) int ownerIndex,
            @ForAll @IntRange(min = 0, max = 6) int arity,
            @ForAll @IntRange(min = 0, max = 5) int descriptorFlavor,
            @ForAll @IntRange(min = 1, max = 500) int line,
            @ForAll boolean fieldMember,
            @ForAll boolean preMarkedGenerated,
            @ForAll long shuffleSeed) {
        TypeId sourceType = new TypeId("sample.Ghost" + ownerIndex);
        CanonicalId generatedId;
        FactNode bytecodeMember;
        if (fieldMember) {
            String descriptor = descriptorFlavor % 2 == 0 ? "[I" : "Ljava/lang/String;";
            FieldId field = new FieldId(sourceType.binaryName(), memberName, descriptor);
            generatedId = field;
            bytecodeMember = JoinTestSupport.fieldNode(LayerId.BYTECODE, field, line, preMarkedGenerated);
        } else {
            MethodId method = new MethodId(sourceType.binaryName(), memberName,
                    JoinTestSupport.methodDescriptor(arity, descriptorFlavor));
            generatedId = method;
            bytecodeMember = JoinTestSupport.methodNode(LayerId.BYTECODE, method, line, line,
                    false, preMarkedGenerated);
        }
        var ast = JoinTestSupport.facts(LayerId.AST,
                List.of(JoinTestSupport.typeNode(LayerId.AST, sourceType, false)), List.of());
        var bytes = JoinTestSupport.facts(LayerId.BYTECODE, JoinTestSupport.shuffled(List.of(
                JoinTestSupport.typeNode(LayerId.BYTECODE, sourceType, false), bytecodeMember), shuffleSeed),
                List.of());

        var graph = (shuffleSeed & 1L) == 0 ? new JoinPolicy().join(ast, bytes)
                : new JoinPolicy().join(bytes, ast);
        var opposite = (shuffleSeed & 1L) == 0 ? new JoinPolicy().join(bytes, ast)
                : new JoinPolicy().join(ast, bytes);
        var node = graph.nodes().stream().filter(candidate -> candidate.id().equals(generatedId))
                .findFirst().orElseThrow();

        assertEquals(opposite, graph);
        assertTrue(JoinTestSupport.bool(node.attrs().get("generated")));
        assertTrue(JoinTestSupport.strings(node.attrs().get("generationReasons"))
                .contains("NO_SOURCE_DECLARATION"));
        assertTrue(JoinTestSupport.strings(node.attrs().get("joinRules")).contains("R03"));
    }

    @Provide
    Arbitrary<String> memberNames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }
}
