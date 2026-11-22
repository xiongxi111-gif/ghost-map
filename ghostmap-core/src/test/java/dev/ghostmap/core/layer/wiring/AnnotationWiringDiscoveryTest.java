package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.bytecode.TestCompiler;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.StringValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationWiringDiscoveryTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void discoversStereotypesMetaStereotypesBeanMethodsAndComponentScanBoundaries() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory, Map.of(
                "sample.api.GhostPort", """
                        package sample.api;
                        public interface GhostPort {}
                        """,
                "sample.config.AppConfig", """
                        package sample.config;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.ComponentScan;
                        import org.springframework.context.annotation.Configuration;
                        import sample.api.GhostPort;
                        @Configuration
                        @ComponentScan(basePackages = "sample.components")
                        public class AppConfig {
                            @Bean(name = {"factoryGhost", "factoryAlias"})
                            GhostPort factoryGhost() { return null; }
                        }
                        """,
                "sample.components.DefaultGhost", """
                        package sample.components;
                        import org.springframework.stereotype.Service;
                        import sample.api.GhostPort;
                        @Service
                        public class DefaultGhost implements GhostPort {}
                        """,
                "sample.components.ExplicitGhost", """
                        package sample.components;
                        import org.springframework.stereotype.Service;
                        import sample.api.GhostPort;
                        @Service("namedGhost")
                        public class ExplicitGhost implements GhostPort {}
                        """,
                "sample.components.HauntedService", """
                        package sample.components;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;
                        import java.lang.annotation.Target;
                        import java.lang.annotation.ElementType;
                        import org.springframework.stereotype.Component;
                        @Target(ElementType.TYPE)
                        @Retention(RetentionPolicy.RUNTIME)
                        @Component
                        public @interface HauntedService {}
                        """,
                "sample.components.MetaGhost", """
                        package sample.components;
                        import sample.api.GhostPort;
                        @HauntedService
                        public class MetaGhost implements GhostPort {}
                        """,
                "sample.outside.OutsideGhost", """
                        package sample.outside;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class OutsideGhost {}
                        """), true);

        LayerFacts facts = new WiringLayer().analyse(LayerInput.forClassRoots(classes));

        for (String id : List.of("b:annotations#appConfig", "b:annotations#defaultGhost",
                "b:annotations#namedGhost", "b:annotations#metaGhost", "b:annotations#factoryGhost")) {
            assertTrue(facts.nodes().stream().anyMatch(node -> node.id().value().equals(id)), id);
        }
        assertFalse(facts.nodes().stream().anyMatch(node ->
                node.id().value().equals("b:annotations#outsideGhost")));
        FactNode factory = facts.nodes().stream()
                .filter(node -> node.id().value().equals("b:annotations#factoryGhost"))
                .findFirst().orElseThrow();
        assertTrue(strings(factory, "aliases").contains("factoryAlias"));
        assertTrue(factory.attrs().get("classFile") instanceof StringValue);
        assertTrue(facts.edges().stream().filter(WiringBindFact.class::isInstance)
                .map(WiringBindFact.class::cast)
                .anyMatch(bind -> bind.from().value().equals("b:annotations#factoryGhost")
                        && bind.target().value().equals("t:sample.api.GhostPort")));
    }

    private static List<String> strings(FactNode node, String name) {
        ArrayValue values = (ArrayValue) node.attrs().get(name);
        return values.values().stream().map(value -> ((StringValue) value).value()).toList();
    }
}
