package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.StringValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiringLayerTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void resolvesImportsAliasesInnerBeansInjectionPointsAndParentInheritance() throws Exception {
        Files.writeString(temporaryDirectory.resolve("imported.xml"), """
                <beans>
                  <bean id="repository" name="repositoryAlias" class="sample.Repository"/>
                  <bean id="collaborator" class="sample.Collaborator"/>
                  <bean id="handler" class="sample.Handler"/>
                </beans>
                """);
        Files.writeString(temporaryDirectory.resolve("main.xml"), """
                <beans>
                  <import resource="imported.xml"/>
                  <alias name="repository" alias="repoAlias"/>
                  <bean id="service" class="sample.Service" scope="prototype">
                    <property name="repository" ref="repoAlias"/>
                    <constructor-arg name="helper" index="0"><ref bean="collaborator"/></constructor-arg>
                    <property name="inner"><bean class="sample.Inner"/></property>
                  </bean>
                  <bean id="baseEcho" class="sample.Echo" scope="prototype" abstract="true">
                    <property name="prefix" value="echo:"/>
                    <property name="announcer" ref="handler"/>
                  </bean>
                  <bean id="childEcho" parent="baseEcho"/>
                </beans>
                """);

        LayerFacts facts = new WiringLayer().analyse(LayerInput.forResourceRoots(temporaryDirectory));

        assertTrue(facts.diagnostics().isEmpty(), () -> "unexpected diagnostics: " + facts.diagnostics());
        FactNode repository = bean(facts, "b:imported.xml#repository");
        assertTrue(repository.location().line() > 0);
        assertTrue(repository.location().file().equals("imported.xml"));
        assertTrue(strings(repository, "aliases").containsAll(List.of("repositoryAlias", "repoAlias")));
        FactNode child = bean(facts, "b:main.xml#childEcho");
        assertEquals("sample.Echo", string(child, "class"));
        assertEquals("prototype", string(child, "scope"));
        assertEquals(List.of("b:main.xml#baseEcho"), strings(child, "inheritanceChain"));
        assertTrue(strings(child, "references").contains("PROPERTY:announcer->handler"));
        assertTrue(strings(child, "properties").contains("PROPERTY:prefix=echo:"));

        FactNode inner = facts.nodes().stream().filter(node -> bool(node, "inner")).findFirst().orElseThrow();
        assertTrue(inner.id().value().startsWith("b:main.xml#service$inner$"));
        assertTrue(facts.edges().stream().filter(WiringInjectionFact.class::isInstance)
                .map(WiringInjectionFact.class::cast)
                .anyMatch(edge -> edge.from().value().equals("b:main.xml#service")
                        && edge.target().value().equals(inner.id().value())
                        && edge.point().kind() == InjectionKind.PROPERTY));
        assertTrue(injection(facts, "b:main.xml#service", "b:imported.xml#repository",
                InjectionKind.PROPERTY).point().name().filter("repository"::equals).isPresent());
        WiringInjectionFact constructor = injection(facts, "b:main.xml#service",
                "b:imported.xml#collaborator", InjectionKind.CONSTRUCTOR_ARGUMENT);
        assertEquals("helper", constructor.point().name().orElseThrow());
        assertEquals(0, constructor.point().index().orElseThrow());

        WiringInjectionFact inherited = injection(facts, "b:main.xml#childEcho",
                "b:imported.xml#handler", InjectionKind.PROPERTY);
        assertEquals(List.of("b:main.xml#baseEcho"), inherited.inheritanceChain().stream()
                .map(Object::toString).toList());
        WiringBindFact childBind = facts.edges().stream().filter(WiringBindFact.class::isInstance)
                .map(WiringBindFact.class::cast)
                .filter(edge -> edge.from().value().equals("b:main.xml#childEcho"))
                .findFirst().orElseThrow();
        assertEquals("t:sample.Echo", childBind.target().value());
        assertEquals(List.of("b:main.xml#baseEcho"), childBind.inheritanceChain().stream()
                .map(Object::toString).toList());
        assertFalse(facts.edges().stream().filter(WiringBindFact.class::isInstance)
                .anyMatch(edge -> edge.from().value().equals("b:main.xml#baseEcho")));
        assertTrue(facts.edges().stream().allMatch(edge -> edge instanceof WiringBindFact bind
                ? bind.evidence().line() > 0 : ((WiringInjectionFact) edge).evidence().line() > 0));
    }

    private static FactNode bean(LayerFacts facts, String id) {
        return facts.nodes().stream().filter(node -> node.id().value().equals(id)).findFirst().orElseThrow();
    }

    private static WiringInjectionFact injection(LayerFacts facts, String from, String target,
                                                  InjectionKind kind) {
        return facts.edges().stream().filter(WiringInjectionFact.class::isInstance)
                .map(WiringInjectionFact.class::cast)
                .filter(edge -> edge.from().value().equals(from) && edge.target().value().equals(target)
                        && edge.point().kind() == kind)
                .findFirst().orElseThrow();
    }

    private static List<String> strings(FactNode node, String name) {
        ArrayValue array = (ArrayValue) node.attrs().get(name);
        return array.values().stream().map(value -> ((StringValue) value).value()).toList();
    }

    private static String string(FactNode node, String name) {
        return ((StringValue) node.attrs().get(name)).value();
    }

    private static boolean bool(FactNode node, String name) {
        return node.attrs().get(name) instanceof BooleanValue value && value.value();
    }
}
