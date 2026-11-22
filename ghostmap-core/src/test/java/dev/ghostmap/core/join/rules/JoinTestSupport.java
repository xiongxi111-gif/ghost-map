package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.core.layer.bytecode.BytecodeCallFact;
import dev.ghostmap.core.layer.bytecode.BytecodeDynamicKind;
import dev.ghostmap.core.layer.bytecode.BytecodeHierarchyFact;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.core.layer.source.DynamicCallKind;
import dev.ghostmap.core.layer.source.SourceCallFact;
import dev.ghostmap.core.layer.source.SourceCallSite;
import dev.ghostmap.core.layer.wiring.InjectionKind;
import dev.ghostmap.core.layer.wiring.WiringBindFact;
import dev.ghostmap.core.layer.wiring.WiringInjectionFact;
import dev.ghostmap.core.layer.wiring.WiringInjectionPoint;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.FieldId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

final class JoinTestSupport {
    private JoinTestSupport() {
    }

    static LayerFacts facts(LayerId layer, List<FactNode> nodes, List<? extends EdgeFact> edges) {
        return new LayerFacts(layer, nodes, List.copyOf(edges), List.of());
    }

    static FactNode typeNode(LayerId layer, TypeId id, boolean interfaceType) {
        return new FactNode(canonical(id), NodeKind.TYPE, simpleName(id), Optional.empty(), Map.of(
                "abstract", new BooleanValue(interfaceType),
                "annotations", new ArrayValue(List.of()),
                "generated", new BooleanValue(false),
                "provenance", strings(layer.name())), Optional.empty());
    }

    static FactNode methodNode(LayerId layer, MethodId id, int firstLine, int lastLine,
                               boolean abstractMethod, boolean generated) {
        Optional<SourceLocation> location = firstLine > 0
                ? Optional.of(new SourceLocation("Sample.java", firstLine, lastLine)) : Optional.empty();
        return new FactNode(canonical(id), NodeKind.METHOD, id.name(), Optional.of(new TypeId(id.binaryOwner())),
                Map.of(
                        "abstract", new BooleanValue(abstractMethod),
                        "annotations", new ArrayValue(List.of()),
                        "descriptor", new StringValue(id.descriptor()),
                        "generated", new BooleanValue(generated),
                        "generationReasons", generated ? strings("TEST_GENERATED") : new ArrayValue(List.of()),
                        "provenance", strings(layer.name())), location);
    }

    static FactNode fieldNode(LayerId layer, FieldId id, int line, boolean generated) {
        return new FactNode(canonical(id), NodeKind.FIELD, id.name(), Optional.of(new TypeId(id.binaryOwner())),
                Map.of(
                        "annotations", new ArrayValue(List.of()),
                        "descriptor", new StringValue(id.descriptor()),
                        "generated", new BooleanValue(generated),
                        "generationReasons", generated ? strings("TEST_GENERATED") : new ArrayValue(List.of()),
                        "provenance", strings(layer.name())),
                line > 0 ? Optional.of(new SourceLocation("Sample.java", line, line)) : Optional.empty());
    }

    static String methodDescriptor(int arity, int flavor) {
        String[] parameters = {"I", "J", "Ljava/lang/String;", "[I", "[[Ljava/lang/Object;", "Z"};
        StringBuilder descriptor = new StringBuilder("(");
        for (int index = 0; index < arity; index++) {
            descriptor.append(parameters[Math.floorMod(flavor + index, parameters.length)]);
        }
        return descriptor.append(")V").toString();
    }

    static <T> List<T> shuffled(List<T> values, long seed) {
        List<T> shuffled = new ArrayList<>(values);
        Collections.shuffle(shuffled, new java.util.Random(seed));
        return List.copyOf(shuffled);
    }

    static SourceCallFact sourceCall(SymbolId from, int line, String receiver, String name, int arity,
                                     Optional<TypeId> receiverType) {
        SourceLocation location = new SourceLocation("Sample.java", line, line);
        DispatchKind dispatch = receiverType.isPresent() ? DispatchKind.INTERFACE : DispatchKind.VIRTUAL;
        return new SourceCallFact(from, new SourceCallSite(from, location, 9),
                new Evidence.Ast("Sample.java", line, receiver + '.' + name + "()"), receiver, name, arity,
                receiverType, DynamicCallKind.NONE, dispatch, List.of());
    }

    static BytecodeCallFact bytecodeCall(MethodId from, MethodId target, int offset, OptionalInt line,
                                         JvmInvocationKind invocation) {
        String owner = target.binaryOwner().replace('.', '/');
        DispatchKind dispatch = switch (invocation) {
            case INVOKESTATIC, INVOKESPECIAL -> DispatchKind.STATIC;
            case INVOKEVIRTUAL -> DispatchKind.VIRTUAL;
            case INVOKEINTERFACE -> DispatchKind.INTERFACE;
            case INVOKEDYNAMIC -> DispatchKind.DYNAMIC;
        };
        return new BytecodeCallFact(canonical(from), Optional.of(canonical(target)), new SiteId(from, offset),
                invocation, owner, target.name(), target.descriptor(),
                new Evidence.Bytecode("Sample.class", offset, invocation.name().toLowerCase(), owner,
                        target.name(), target.descriptor()), line, Optional.of("Sample.java"),
                BytecodeDynamicKind.NONE, dispatch, Optional.empty());
    }

    static BytecodeHierarchyFact implementsEdge(TypeId implementation, TypeId contract) {
        return new BytecodeHierarchyFact(EdgeKind.IMPLEMENTS, canonical(implementation), canonical(contract),
                "Sample.class");
    }

    static BytecodeCallFact dynamicBytecodeCall(MethodId from, MethodId target, int offset, int line,
                                                  JvmInvocationKind invocation,
                                                  BytecodeDynamicKind dynamicKind,
                                                  Optional<String> constantKey) {
        String owner = target.binaryOwner().replace('.', '/');
        DispatchKind dispatch = switch (dynamicKind) {
            case GET_BEAN, CLASS_LOADING, METHOD_HANDLE, INVOKEDYNAMIC -> DispatchKind.DYNAMIC;
            case REFLECTION -> DispatchKind.REFLECTION;
            case REGISTRY -> DispatchKind.REGISTRY;
            case NONE -> DispatchKind.VIRTUAL;
        };
        return new BytecodeCallFact(canonical(from), Optional.of(canonical(target)), new SiteId(from, offset),
                invocation, owner, target.name(), target.descriptor(),
                new Evidence.Bytecode("Sample.class", offset, invocation.name().toLowerCase(), owner,
                        target.name(), target.descriptor()), OptionalInt.of(line), Optional.of("Sample.java"),
                dynamicKind, dispatch, Optional.empty(), constantKey);
    }

    static WiringBindFact wiringBind(BeanId bean, TypeId type) {
        return new WiringBindFact(canonical(bean), canonical(type),
                new Evidence.Wiring("wiring.xml", 3, bean), List.of());
    }

    static WiringInjectionFact wiringInjection(BeanId owner, BeanId target, String pointName) {
        return new WiringInjectionFact(canonical(owner), canonical(target),
                new WiringInjectionPoint(InjectionKind.PROPERTY, Optional.of(pointName), OptionalInt.empty()),
                new Evidence.Wiring("wiring.xml", 5, target), List.of());
    }

    static SymbolId canonical(CanonicalId id) {
        return new SymbolId.Canonical(id);
    }

    static boolean bool(JsonValue value) {
        return value instanceof BooleanValue bool && bool.value();
    }

    static List<String> strings(JsonValue value) {
        if (!(value instanceof ArrayValue array)) {
            return List.of();
        }
        return array.values().stream().map(item -> ((StringValue) item).value()).toList();
    }

    private static ArrayValue strings(String value) {
        return new ArrayValue(List.of(new StringValue(value)));
    }

    private static String simpleName(TypeId type) {
        int separator = Math.max(type.binaryName().lastIndexOf('.'), type.binaryName().lastIndexOf('$'));
        return type.binaryName().substring(separator + 1);
    }
}
