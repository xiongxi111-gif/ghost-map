package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.EvidenceContribution;
import dev.ghostmap.core.join.JoinContext;
import dev.ghostmap.core.join.JoinRule;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.DiagnosticSeverity;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.core.layer.bytecode.BytecodeCallFact;
import dev.ghostmap.core.layer.bytecode.BytecodeDynamicKind;
import dev.ghostmap.core.layer.wiring.WiringBindFact;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Confidence;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.EdgeIds;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.Node;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** R07: materialize every detected dynamic API site as resolved or explicitly UNRESOLVED. */
public final class R07DynamicSites implements JoinRule {
    public static final String ID = "R07";
    public static final double WIRING_WEIGHT = 0.80;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(JoinContext context) {
        BeanIndex beans = beanIndex(context);
        Map<String, JoinedEdge> dynamicCalls = new LinkedHashMap<>();
        context.edges().stream()
                .filter(edge -> edge.bytecodeFact().map(BytecodeCallFact::dynamicKind)
                        .filter(kind -> kind != BytecodeDynamicKind.NONE
                                && kind != BytecodeDynamicKind.INVOKEDYNAMIC).isPresent())
                .sorted(Comparator.comparing(edge -> edge.bytecodeSite().orElseThrow().value()))
                .forEach(edge -> dynamicCalls.putIfAbsent(edge.bytecodeSite().orElseThrow().value(), edge));

        List<Edge> dynamicEdges = new ArrayList<>();
        List<Node> nodes = new ArrayList<>(context.nodes());
        Set<String> existingNodes = new LinkedHashSet<>();
        nodes.forEach(node -> existingNodes.add(node.id().value()));
        for (JoinedEdge call : dynamicCalls.values()) {
            BytecodeCallFact bytecode = call.bytecodeFact().orElseThrow();
            SiteId site = bytecode.site();
            Optional<String> constantKey = bytecode.constantStringArgument()
                    .or(() -> call.sourceFact().flatMap(source -> source.stringLiterals().stream().findFirst()));
            Resolution resolution = resolve(bytecode.dynamicKind(), constantKey, beans);
            List<Evidence> evidence = new ArrayList<>(call.evidence());
            List<EvidenceContribution> contributions = new ArrayList<>(call.contributions());
            resolution.wiringEvidence().ifPresent(item -> {
                evidence.add(item);
                contributions.add(new EvidenceContribution(LayerId.WIRING, WIRING_WEIGHT,
                        "WIRING_DYNAMIC_KEY_RESOLUTION"));
            });
            Set<LayerId> provenance = EnumSet.noneOf(LayerId.class);
            evidence.forEach(item -> provenance.add(item.layer()));
            List<String> rules = new ArrayList<>(call.rules());
            if (!rules.contains(ID)) {
                rules.add(ID);
            }
            double confidence = Confidence.noisyOr(contributions.stream()
                    .map(EvidenceContribution::weight).toList());
            List<CanonicalId> candidates = resolution.candidates().stream()
                    .map(candidate -> (CanonicalId) candidate).toList();
            if (resolution.target().isPresent()) {
                CanonicalId target = resolution.target().orElseThrow();
                dynamicEdges.add(new Edge.Resolved(
                        EdgeIds.canonical(EdgeKind.CALLS, site, Optional.of(target), Optional.of(site)),
                        EdgeKind.CALLS, site, target, Optional.of(site), provenance, confidence, rules,
                        evidence, bytecode.dispatch(), candidates, EdgeStatus.PREDICTED));
            } else {
                dynamicEdges.add(new Edge.Unresolved(
                        EdgeIds.canonical(EdgeKind.UNRESOLVED, site, Optional.empty(), Optional.of(site)),
                        site, site, provenance, confidence, rules, evidence, bytecode.dispatch(), candidates,
                        constantKey));
                context.addDiagnostic(new Diagnostic(DiagnosticCode.DYNAMIC_SITE_UNRESOLVED,
                        DiagnosticSeverity.WARNING, bytecode.evidence().classFile(),
                        bytecode.sourceLine().orElse(0), "dynamic site remains unresolved: " + site.value()
                                + constantKey.map(key -> " key=" + key).orElse("")));
            }
            if (existingNodes.add(site.value())) {
                nodes.add(siteNode(site, bytecode, constantKey));
            }
        }
        context.setNodes(nodes);
        context.setDynamicEdges(dynamicEdges);
    }

    private static Resolution resolve(BytecodeDynamicKind kind, Optional<String> constantKey, BeanIndex beans) {
        if (kind == BytecodeDynamicKind.CLASS_LOADING && constantKey.isPresent()) {
            try {
                TypeId target = new TypeId(constantKey.orElseThrow().replace('/', '.'));
                return new Resolution(Optional.of(target), List.of(), Optional.empty());
            } catch (IllegalArgumentException exception) {
                return Resolution.unresolved(List.of());
            }
        }
        if ((kind == BytecodeDynamicKind.GET_BEAN || kind == BytecodeDynamicKind.REGISTRY)
                && constantKey.isPresent()) {
            List<BeanId> candidates = beans.byName().getOrDefault(constantKey.orElseThrow(), List.of());
            if (candidates.size() == 1) {
                BeanId target = candidates.get(0);
                return new Resolution(Optional.of(target), candidates,
                        Optional.ofNullable(beans.evidence().get(target)));
            }
            return Resolution.unresolved(candidates);
        }
        return Resolution.unresolved(List.of());
    }

    private static Node siteNode(SiteId site, BytecodeCallFact bytecode, Optional<String> key) {
        Map<String, JsonValue> attrs = new LinkedHashMap<>();
        attrs.put("annotations", new ArrayValue(List.of()));
        attrs.put("dynamicKind", new StringValue(bytecode.dynamicKind().name()));
        attrs.put("generated", new BooleanValue(false));
        key.ifPresent(value -> attrs.put("partialKey", new StringValue(value)));
        Optional<SourceLocation> location = bytecode.sourceFile().flatMap(file -> bytecode.sourceLine().isPresent()
                ? Optional.of(new SourceLocation(file, bytecode.sourceLine().orElseThrow(),
                bytecode.sourceLine().orElseThrow())) : Optional.empty());
        return new Node(site, NodeKind.SITE, bytecode.methodName() + '@' + site.bytecodeOffset(),
                Optional.of(new TypeId(site.method().binaryOwner())), attrs, location);
    }

    private static BeanIndex beanIndex(JoinContext context) {
        Map<String, List<BeanId>> byName = new HashMap<>();
        Map<BeanId, Evidence.Wiring> evidence = new HashMap<>();
        context.nodeFacts().stream().filter(fact -> fact.layer() == LayerId.WIRING)
                .map(JoinContext.NodeFact::node)
                .filter(node -> node.id() instanceof SymbolId.Canonical canonical
                        && canonical.id() instanceof BeanId)
                .forEach(node -> {
                    BeanId bean = (BeanId) ((SymbolId.Canonical) node.id()).id();
                    addName(byName, bean.beanId(), bean);
                    strings(node.attrs().get("aliases")).forEach(alias -> addName(byName, alias, bean));
                    node.sourceLocation().ifPresent(location -> evidence.putIfAbsent(bean,
                            new Evidence.Wiring(location.file(), location.line(), bean)));
                });
        context.edgeFacts().stream().filter(entry -> entry.fact() instanceof WiringBindFact)
                .map(entry -> (WiringBindFact) entry.fact()).forEach(bind -> {
                    BeanId bean = (BeanId) ((SymbolId.Canonical) bind.from()).id();
                    evidence.put(bean, bind.evidence());
                });
        Map<String, List<BeanId>> sorted = new HashMap<>();
        byName.forEach((name, values) -> sorted.put(name, values.stream().distinct()
                .sorted(Comparator.comparing(BeanId::value)).toList()));
        return new BeanIndex(Map.copyOf(sorted), Map.copyOf(evidence));
    }

    private static void addName(Map<String, List<BeanId>> index, String name, BeanId bean) {
        index.computeIfAbsent(name, ignored -> new ArrayList<>()).add(bean);
    }

    private static List<String> strings(JsonValue value) {
        if (!(value instanceof ArrayValue array)) {
            return List.of();
        }
        return array.values().stream().filter(StringValue.class::isInstance).map(StringValue.class::cast)
                .map(StringValue::value).toList();
    }

    private record BeanIndex(Map<String, List<BeanId>> byName, Map<BeanId, Evidence.Wiring> evidence) {
    }

    private record Resolution(Optional<CanonicalId> target, List<BeanId> candidates,
                              Optional<Evidence.Wiring> wiringEvidence) {
        private Resolution {
            candidates = List.copyOf(candidates);
        }

        static Resolution unresolved(List<BeanId> candidates) {
            return new Resolution(Optional.empty(), candidates, Optional.empty());
        }
    }
}
