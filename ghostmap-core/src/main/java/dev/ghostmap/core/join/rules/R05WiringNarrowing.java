package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.EvidenceContribution;
import dev.ghostmap.core.join.JoinContext;
import dev.ghostmap.core.join.JoinRule;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.DiagnosticSeverity;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.core.layer.wiring.WiringBindFact;
import dev.ghostmap.core.layer.wiring.WiringInjectionFact;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.LayerId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** R05: narrow virtual candidates using resolved field or constructor injection. */
public final class R05WiringNarrowing implements JoinRule {
    public static final String ID = "R05";
    public static final double WIRING_WEIGHT = 0.80;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(JoinContext context) {
        Map<BeanId, TypeId> beanTypes = beanTypes(context);
        List<WiringInjectionFact> injections = context.edgeFacts().stream()
                .filter(entry -> entry.fact() instanceof WiringInjectionFact)
                .map(entry -> (WiringInjectionFact) entry.fact())
                .sorted(Comparator.comparing(fact -> fact.from().value() + '|' + fact.point() + '|'
                        + fact.target().value()))
                .toList();
        List<JoinedEdge> narrowed = new ArrayList<>();
        for (JoinedEdge edge : context.edges()) {
            Optional<Selection> selection = selection(edge, beanTypes, injections, context);
            if (selection.isEmpty()) {
                narrowed.add(edge);
                continue;
            }
            Selection selected = selection.orElseThrow();
            boolean candidateExists = edge.candidates().stream().filter(MethodId.class::isInstance)
                    .map(MethodId.class::cast)
                    .anyMatch(method -> method.binaryOwner().equals(selected.type().binaryName()));
            if (!candidateExists) {
                narrowed.add(edge);
                continue;
            }
            if (edge.target().filter(MethodId.class::isInstance).map(MethodId.class::cast)
                    .filter(method -> method.binaryOwner().equals(selected.type().binaryName())).isPresent()) {
                List<EvidenceContribution> contributions = new ArrayList<>(edge.contributions());
                contributions.add(new EvidenceContribution(LayerId.WIRING, WIRING_WEIGHT,
                        "WIRING_SELECTS_IMPLEMENTATION"));
                JoinedEdge selectedEdge = edge.withSupplementaryEvidence(selected.injection().evidence(),
                                contributions, edge.target().orElseThrow(), EdgeStatus.PREDICTED)
                        .withRule(ID);
                narrowed.add(selectedEdge);
            }
        }
        context.setEdges(narrowed);
    }

    private static Optional<Selection> selection(JoinedEdge edge, Map<BeanId, TypeId> beanTypes,
                                                 List<WiringInjectionFact> injections,
                                                 JoinContext context) {
        if (edge.invocationKind().filter(kind -> kind == JvmInvocationKind.INVOKEVIRTUAL
                || kind == JvmInvocationKind.INVOKEINTERFACE).isEmpty()
                || edge.sourceFact().isEmpty()
                || !(edge.from() instanceof SymbolId.Canonical caller)
                || !(caller.id() instanceof MethodId callerMethod)) {
            return Optional.empty();
        }
        String receiver = edge.sourceFact().orElseThrow().textualReceiver();
        String pointName = receiver.startsWith("this.") ? receiver.substring("this.".length()) : receiver;
        List<BeanId> ownerBeans = beanTypes.entrySet().stream()
                .filter(entry -> entry.getValue().binaryName().equals(callerMethod.binaryOwner()))
                .map(Map.Entry::getKey).toList();
        List<Selection> selections = injections.stream()
                .filter(injection -> injection.from() instanceof SymbolId.Canonical source
                        && source.id() instanceof BeanId bean && ownerBeans.contains(bean))
                .filter(injection -> injection.point().name().filter(pointName::equals).isPresent())
                .filter(injection -> injection.target() instanceof SymbolId.Canonical target
                        && target.id() instanceof BeanId && beanTypes.containsKey((BeanId) target.id()))
                .map(injection -> {
                    BeanId target = (BeanId) ((SymbolId.Canonical) injection.target()).id();
                    return new Selection(beanTypes.get(target), injection);
                })
                .toList();
        Set<TypeId> selectedTypes = selections.stream().map(Selection::type)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (selectedTypes.size() != 1) {
            if (selectedTypes.size() > 1) {
                context.addDiagnostic(new Diagnostic(DiagnosticCode.WIRING_NARROWING_AMBIGUOUS,
                        DiagnosticSeverity.WARNING,
                        edge.sourceFact().orElseThrow().evidence().file(),
                        edge.sourceFact().orElseThrow().evidence().line(),
                        "multiple wiring targets remain for receiver " + receiver + ": " + selectedTypes));
            }
            return Optional.empty();
        }
        TypeId type = selectedTypes.iterator().next();
        WiringInjectionFact evidence = selections.stream().filter(candidate -> candidate.type().equals(type))
                .map(Selection::injection)
                .findFirst().orElseThrow();
        return Optional.of(new Selection(type, evidence));
    }

    private static Map<BeanId, TypeId> beanTypes(JoinContext context) {
        Map<BeanId, TypeId> types = new HashMap<>();
        context.edgeFacts().stream().filter(entry -> entry.fact() instanceof WiringBindFact)
                .map(entry -> (WiringBindFact) entry.fact()).forEach(bind -> {
                    BeanId bean = (BeanId) ((SymbolId.Canonical) bind.from()).id();
                    TypeId type = (TypeId) ((SymbolId.Canonical) bind.target()).id();
                    types.put(bean, type);
                });
        return Map.copyOf(types);
    }

    private record Selection(TypeId type, WiringInjectionFact injection) {
    }
}
