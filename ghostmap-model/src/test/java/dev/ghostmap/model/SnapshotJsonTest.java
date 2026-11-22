package dev.ghostmap.model;

import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.FieldId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Evidence.Ast;
import dev.ghostmap.model.Evidence.Bytecode;
import dev.ghostmap.model.Evidence.Runtime;
import dev.ghostmap.model.Evidence.Wiring;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.NumberValue;
import dev.ghostmap.model.JsonValue.ObjectValue;
import dev.ghostmap.model.JsonValue.StringValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotJsonTest {
    private static final String HASH_A = "sha256:" + "a".repeat(64);
    private static final String HASH_B = "sha256:" + "b".repeat(64);

    @Test
    void representativeSnapshotRoundTripsWithoutThirdPartyJson() {
        Snapshot snapshot = representativeSnapshot();

        String json = SnapshotJson.toJson(snapshot);
        Snapshot restored = SnapshotJson.fromJson(json);

        assertEquals(snapshot, restored);
        assertEquals(json, SnapshotJson.toJson(restored));
        assertTrue(json.contains("\"to\":null"));
        assertTrue(json.contains("\"layer\":\"RUNTIME\""));
    }

    @Test
    void rejectsNullAttributeValues() {
        String json = SnapshotJson.toJson(representativeSnapshot())
                .replace("\"generated\":false", "\"generated\":null");
        assertThrows(IllegalArgumentException.class, () -> SnapshotJson.fromJson(json));
    }

    private static Snapshot representativeSnapshot() {
        TypeId controller = new TypeId("dev.ghostmap.fixture.OwnerController$1");
        MethodId showOwner = new MethodId(controller.binaryName(), "showOwner",
                "(I)Lorg/springframework/web/servlet/ModelAndView;");
        MethodId findOwner = new MethodId("dev.ghostmap.fixture.JdbcOwnerRepository", "findById",
                "(I)Ldev/ghostmap/fixture/Owner;");
        FieldId owners = new FieldId(controller.binaryName(), "owners",
                "Ldev/ghostmap/fixture/OwnerRepository;");
        SiteId site = new SiteId(showOwner, 42);
        BeanId bean = new BeanId("business-config.xml", "ownerRepository");

        Map<String, JsonValue> attrs = Map.of(
                "annotations", new ArrayValue(List.of(new StringValue("GetMapping"))),
                "generated", new BooleanValue(false),
                "role", new StringValue("CONTROLLER"),
                "branchCount", new NumberValue(BigDecimal.valueOf(2)),
                "meta", new ObjectValue(Map.of("escaped", new StringValue("quote \" and newline\n"))));

        List<Node> nodes = List.of(
                new Node(controller, NodeKind.TYPE, "OwnerController", Optional.empty(), Map.of(),
                        Optional.of(new SourceLocation("src/main/java/dev/ghostmap/fixture/OwnerController.java", 1, 30))),
                new Node(showOwner, NodeKind.METHOD, "showOwner", Optional.of(controller), attrs,
                        Optional.of(new SourceLocation("src/main/java/dev/ghostmap/fixture/OwnerController.java", 15, 20))),
                new Node(owners, NodeKind.FIELD, "owners", Optional.of(controller), Map.of(), Optional.empty()),
                new Node(site, NodeKind.SITE, "findById call", Optional.of(controller), Map.of(),
                        Optional.of(new SourceLocation("src/main/java/dev/ghostmap/fixture/OwnerController.java", 17, 17))),
                new Node(bean, NodeKind.BEAN, "ownerRepository", Optional.empty(), Map.of(),
                        Optional.of(new SourceLocation("business-config.xml", 31, 31))));

        List<Evidence> resolvedEvidence = List.of(
                new Ast("src/main/java/dev/ghostmap/fixture/OwnerController.java", 17,
                        "this.owners.findById(ownerId)"),
                new Bytecode("dev/ghostmap/fixture/OwnerController$1.class", 17, "invokeinterface",
                        "dev/ghostmap/fixture/OwnerRepository", "findById", "(I)Ldev/ghostmap/fixture/Owner;"),
                new Wiring("business-config.xml", 31, bean),
                new Runtime("run-1", 12));
        Set<LayerId> allLayers = Set.of(LayerId.AST, LayerId.BYTECODE, LayerId.WIRING, LayerId.RUNTIME);
        double confidence = Confidence.noisyOr(0.55, 0.85, 0.80, 0.99);
        Edge resolved = new Edge.Resolved(
                EdgeIds.canonical(EdgeKind.CALLS, showOwner, Optional.of(findOwner), Optional.of(site)),
                EdgeKind.CALLS, showOwner, findOwner, Optional.of(site), allLayers, confidence,
                List.of("R02", "R05", "R06"), resolvedEvidence, DispatchKind.INTERFACE,
                List.of(findOwner), EdgeStatus.CONFIRMED);

        List<Evidence> unresolvedEvidence = List.of(
                new Ast("src/main/java/dev/ghostmap/fixture/OwnerController.java", 19,
                        "method.invoke(target, ownerId)"));
        Edge unresolved = new Edge.Unresolved(
                EdgeIds.canonical(EdgeKind.UNRESOLVED, site, Optional.empty(), Optional.of(site)),
                site, site, Set.of(LayerId.AST), 0.30, List.of("R07"), unresolvedEvidence,
                DispatchKind.REFLECTION, List.of(), Optional.of("find"));

        BlindSpotReport report = new BlindSpotReport(
                Map.of("AST+BYTECODE+WIRING+RUNTIME", 1L, "AST", 1L),
                Map.of(UnresolvedSiteKind.GETBEAN, 0L, UnresolvedSiteKind.REFLECTION, 1L,
                        UnresolvedSiteKind.REGISTRY, 0L, UnresolvedSiteKind.PROXY, 0L,
                        UnresolvedSiteKind.OTHER, 0L),
                0,
                new ExercisedCoverage(2, 5),
                new StaticRecall(OptionalDouble.of(0.5), OptionalDouble.of(1.0)),
                "Recall is measured only over code exercised by the recorded workloads.");

        return new Snapshot(
                Snapshot.SCHEMA,
                HASH_A,
                new CodeVersion("https://github.com/example/ghost-map", "abc123", false, HASH_B,
                        Instant.parse("2026-11-20T10:00:00Z")),
                new ToolInfo("ghostmap", "0.1.0", "jp/v1"),
                allLayers,
                List.of(new RuntimeRun("run-1", "haunted-house-it", List.of("dev.ghostmap.fixture"))),
                nodes,
                List.of(resolved, unresolved),
                report);
    }
}
