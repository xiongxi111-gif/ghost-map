package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.bytecode.TestCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombinedWiringLayerTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void remoteImportResourceIsDiagnosedWithoutFetching() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory.resolve("remote-import"), Map.of(
                "sample.RemoteConfig", """
                        package sample;
                        import org.springframework.context.annotation.Configuration;
                        import org.springframework.context.annotation.ImportResource;
                        @Configuration
                        @ImportResource("https://example.invalid/never.xml")
                        public class RemoteConfig {}
                        """), true);

        LayerFacts facts = new WiringLayer().analyse(LayerInput.forClassRoots(classes));

        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.XML_IMPORT_OUTSIDE_ROOT));
        assertTrue(facts.nodes().stream().anyMatch(node ->
                node.id().value().equals("b:annotations#remoteConfig")));
    }

    @Test
    void annotationBeansResolveXmlReferencesOnlyWhenBothInputsAreAnalysed() throws Exception {
        Path compileRoot = temporaryDirectory.resolve("compiled");
        Path classes = TestCompiler.compile(compileRoot, Map.of(
                "sample.RattlingAnnouncer", """
                        package sample;
                        import org.springframework.stereotype.Component;
                        @Component("rattlingAnnouncer")
                        public class RattlingAnnouncer {}
                        """,
                "sample.Config", """
                        package sample;
                        import org.springframework.context.annotation.Configuration;
                        import org.springframework.context.annotation.ImportResource;
                        @Configuration
                        @ImportResource("classpath:wiring.xml")
                        public class Config {}
                        """,
                "sample.Echo", "package sample; public class Echo {}",
                "sample.EchoConsumer", """
                        package sample;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.stereotype.Component;
                        @Component public class EchoConsumer { @Autowired Echo echo; }
                        """), true);
        Path resources = Files.createDirectory(temporaryDirectory.resolve("resources"));
        Files.writeString(resources.resolve("wiring.xml"), """
                <beans>
                  <bean id="echoTemplate" class="sample.Echo" abstract="true">
                    <property name="announcer" ref="rattlingAnnouncer"/>
                  </bean>
                  <bean id="ancestralEcho" parent="echoTemplate"/>
                </beans>
                """);

        LayerFacts xmlOnly = new WiringLayer().analyse(LayerInput.forResourceRoots(resources));
        LayerFacts combined = new WiringLayer().analyse(
                new LayerInput(Optional.empty(), List.of(classes), List.of(resources)));

        assertTrue(xmlOnly.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.XML_REFERENCE_MISSING));
        assertTrue(xmlOnly.edges().stream().anyMatch(UnresolvedWiringFact.class::isInstance));
        assertFalse(combined.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.XML_REFERENCE_MISSING
                        || diagnostic.code() == DiagnosticCode.XML_IMPORT_MISSING));
        assertTrue(combined.edges().stream().filter(WiringInjectionFact.class::isInstance)
                .map(WiringInjectionFact.class::cast)
                .anyMatch(edge -> edge.from().value().equals("b:wiring.xml#ancestralEcho")
                        && edge.target().value().equals("b:annotations#rattlingAnnouncer")
                        && edge.inheritanceChain().stream().map(Object::toString)
                        .toList().equals(List.of("b:wiring.xml#echoTemplate"))));
        assertTrue(combined.edges().stream().filter(WiringBindFact.class::isInstance)
                .map(WiringBindFact.class::cast)
                .anyMatch(edge -> edge.from().value().equals("b:annotations#rattlingAnnouncer")
                        && edge.target().value().equals("t:sample.RattlingAnnouncer")));
        assertTrue(combined.edges().stream().filter(WiringInjectionFact.class::isInstance)
                .map(WiringInjectionFact.class::cast)
                .anyMatch(edge -> edge.from().value().equals("b:annotations#echoConsumer")
                        && edge.target().value().equals("b:wiring.xml#ancestralEcho")));
    }
}
