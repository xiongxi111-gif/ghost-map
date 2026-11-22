package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.bytecode.TestCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationWiringInjectionTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void resolvesFieldsConstructorsSettersInjectResourceQualifiersAndPrimary() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory, Map.ofEntries(
                Map.entry("sample.Port", "package sample; public interface Port {}"),
                Map.entry("sample.Alpha", """
                        package sample;
                        import org.springframework.stereotype.Service;
                        @Service("alpha") public class Alpha implements Port {}
                        """),
                Map.entry("sample.Beta", """
                        package sample;
                        import org.springframework.context.annotation.Primary;
                        import org.springframework.stereotype.Service;
                        @Primary @Service("beta") public class Beta implements Port {}
                        """),
                Map.entry("sample.FieldConsumer", """
                        package sample;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.beans.factory.annotation.Qualifier;
                        import org.springframework.stereotype.Component;
                        @Component public class FieldConsumer {
                            @Autowired @Qualifier("alpha") Port port;
                        }
                        """),
                Map.entry("sample.ConstructorConsumer", """
                        package sample;
                        import org.springframework.stereotype.Component;
                        @Component public class ConstructorConsumer {
                            ConstructorConsumer(Port port) {}
                        }
                        """),
                Map.entry("sample.InjectConsumer", """
                        package sample;
                        import jakarta.inject.Inject;
                        import jakarta.inject.Named;
                        import org.springframework.stereotype.Component;
                        @Component public class InjectConsumer {
                            @Inject InjectConsumer(@Named("alpha") Port port) {}
                        }
                        """),
                Map.entry("sample.SetterConsumer", """
                        package sample;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.beans.factory.annotation.Qualifier;
                        import org.springframework.stereotype.Component;
                        @Component public class SetterConsumer {
                            @Autowired void setPort(@Qualifier("alpha") Port port) {}
                        }
                        """),
                Map.entry("sample.ResourceConsumer", """
                        package sample;
                        import jakarta.annotation.Resource;
                        import org.springframework.stereotype.Component;
                        @Component public class ResourceConsumer {
                            @Resource(name="alpha") Port port;
                        }
                        """),
                Map.entry("javax.inject.Inject", """
                        package javax.inject;
                        import java.lang.annotation.*;
                        @Target({ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.FIELD})
                        @Retention(RetentionPolicy.RUNTIME)
                        public @interface Inject {}
                        """),
                Map.entry("javax.inject.Named", """
                        package javax.inject;
                        import java.lang.annotation.*;
                        @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
                        @Retention(RetentionPolicy.RUNTIME)
                        public @interface Named { String value() default ""; }
                        """),
                Map.entry("javax.annotation.Resource", """
                        package javax.annotation;
                        import java.lang.annotation.*;
                        @Target({ElementType.FIELD, ElementType.METHOD})
                        @Retention(RetentionPolicy.RUNTIME)
                        public @interface Resource { String name() default ""; }
                        """),
                Map.entry("sample.JavaxConsumer", """
                        package sample;
                        import org.springframework.stereotype.Component;
                        @Component public class JavaxConsumer {
                            @javax.inject.Inject JavaxConsumer(@javax.inject.Named("alpha") Port port) {}
                        }
                        """),
                Map.entry("sample.JavaxResourceConsumer", """
                        package sample;
                        import org.springframework.stereotype.Component;
                        @Component public class JavaxResourceConsumer {
                            @javax.annotation.Resource(name="alpha") Port port;
                        }
                        """)), true);

        LayerFacts facts = new WiringLayer().analyse(LayerInput.forClassRoots(classes));

        assertInjection(facts, "fieldConsumer", "alpha");
        assertInjection(facts, "constructorConsumer", "beta");
        assertInjection(facts, "injectConsumer", "alpha");
        assertInjection(facts, "setterConsumer", "alpha");
        assertInjection(facts, "resourceConsumer", "alpha");
        assertInjection(facts, "javaxConsumer", "alpha");
        assertInjection(facts, "javaxResourceConsumer", "alpha");
        assertTrue(facts.edges().stream().noneMatch(UnresolvedWiringFact.class::isInstance));
    }

    private static void assertInjection(LayerFacts facts, String owner, String target) {
        List<WiringInjectionFact> matches = facts.edges().stream().filter(WiringInjectionFact.class::isInstance)
                .map(WiringInjectionFact.class::cast)
                .filter(edge -> edge.from().value().equals("b:annotations#" + owner)
                        && edge.target().value().equals("b:annotations#" + target)).toList();
        assertEquals(1, matches.size(), owner + " -> " + target);
    }
}
