package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.bytecode.BytecodeLayer;
import dev.ghostmap.core.layer.bytecode.TestCompiler;
import dev.ghostmap.core.layer.source.SourceLayer;
import dev.ghostmap.model.LayerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class R03GeneratedMembersTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void lombokGeneratedMembersJoinToSourceCallSites() throws Exception {
        Path workspace = temporaryDirectory.resolve("lombok-join");
        Path classes = TestCompiler.compile(workspace, Map.of("sample.GhostData", """
                package sample;
                import lombok.Data;
                import lombok.RequiredArgsConstructor;

                @Data
                public class GhostData {
                    private String name;
                }

                class GhostCaller {
                    String touch(GhostData ghost) {
                        ghost.setName("seen");
                        return ghost.getName();
                    }
                }

                @RequiredArgsConstructor
                class GhostHolder {
                    private final GhostData ghost;
                }
                """), true);
        var sourceFacts = new SourceLayer().analyse(new LayerInput(workspace.resolve("src")));
        var bytecodeFacts = new BytecodeLayer().analyse(LayerInput.forClassRoots(classes));

        var graph = new JoinPolicy().join(sourceFacts, bytecodeFacts);

        for (String id : Set.of(
                "m:sample.GhostData#getName()Ljava/lang/String;",
                "m:sample.GhostData#setName(Ljava/lang/String;)V",
                "m:sample.GhostHolder#<init>(Lsample/GhostData;)V")) {
            var node = graph.nodes().stream().filter(candidate -> candidate.id().value().equals(id))
                    .findFirst().orElseThrow();
            assertTrue(JoinTestSupport.bool(node.attrs().get("generated")), id);
            assertTrue(JoinTestSupport.strings(node.attrs().get("joinRules")).contains("R03"), id);
        }
        for (String method : Set.of("getName", "setName")) {
            var edge = graph.edges().stream()
                    .filter(candidate -> candidate.target().map(target -> target.value().contains('#' + method))
                            .orElse(false))
                    .findFirst().orElseThrow();
            assertTrue(edge.rules().contains("R02"));
            assertTrue(edge.rules().contains("R03"));
            assertTrue(edge.provenance().equals(Set.of(LayerId.AST, LayerId.BYTECODE)));
            assertTrue(edge.evidence().size() == 2);
        }
    }
}
