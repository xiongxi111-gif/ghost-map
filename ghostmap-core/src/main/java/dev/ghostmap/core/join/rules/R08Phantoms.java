package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinContext;
import dev.ghostmap.core.join.JoinRule;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.core.layer.runtime.RuntimePair;
import dev.ghostmap.core.layer.wiring.WiringBindFact;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Confidence;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.EdgeIds;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.LayerId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** R08: reconcile post-R06 dynamic edges, then preserve every unmatched runtime pair as a phantom. */
public final class R08Phantoms implements JoinRule {
    public static final String ID = "R08";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(JoinContext context) {
        List<Edge> dynamic = new ArrayList<>(context.dynamicEdges());
        Map<BeanId, TypeId> beanTypes = beanTypes(context);
        for (RuntimeJoinSupport.RuntimeAggregate aggregate : RuntimeJoinSupport.aggregates(context)) {
            RuntimePair pair = aggregate.pair();
            if (context.runtimeConsumed(pair)) {
                continue;
            }
            Integer match = matchingDynamicEdge(dynamic, aggregate, beanTypes);
            if (match != null) {
                dynamic.set(match, confirmDynamic((Edge.Resolved) dynamic.get(match), aggregate));
                context.markRuntimeConsumed(pair);
                continue;
            }
            dynamic.add(phantom(dynamic, aggregate));
            context.markRuntimeConsumed(pair);
        }
        context.setDynamicEdges(dynamic);
    }

    private static Integer matchingDynamicEdge(List<Edge> edges,
                                                RuntimeJoinSupport.RuntimeAggregate aggregate,
                                                Map<BeanId, TypeId> beanTypes) {
        RuntimePair pair = aggregate.pair();
        DispatchKind observed = RuntimeJoinSupport.dispatch(aggregate, DispatchKind.UNKNOWN);
        return java.util.stream.IntStream.range(0, edges.size()).boxed()
                .filter(index -> edges.get(index) instanceof Edge.Resolved)
                .filter(index -> edges.get(index).status() != EdgeStatus.CONFIRMED)
                .filter(index -> caller(edges.get(index)).filter(pair.caller()::equals).isPresent())
                .filter(index -> targetMatches((Edge.Resolved) edges.get(index), pair.callee(), observed, beanTypes))
                .sorted(Comparator.<Integer>comparingInt(index -> edges.get(index).site()
                                .map(SiteId::bytecodeOffset).orElse(Integer.MAX_VALUE))
                        .thenComparing(index -> edges.get(index).id().value()))
                .findFirst().orElse(null);
    }

    private static boolean targetMatches(Edge.Resolved edge, MethodId callee, DispatchKind observed,
                                         Map<BeanId, TypeId> beanTypes) {
        CanonicalId target = edge.to();
        if (target.equals(callee)) {
            return true;
        }
        if (observed == DispatchKind.UNKNOWN || edge.dispatch() != observed) {
            return false;
        }
        if (target instanceof TypeId type) {
            return type.binaryName().equals(callee.binaryOwner());
        }
        if (target instanceof BeanId bean) {
            return Optional.ofNullable(beanTypes.get(bean)).map(TypeId::binaryName)
                    .filter(callee.binaryOwner()::equals).isPresent();
        }
        return false;
    }

    private static Edge.Resolved confirmDynamic(Edge.Resolved edge,
                                                RuntimeJoinSupport.RuntimeAggregate aggregate) {
        RuntimePair pair = aggregate.pair();
        List<Evidence> evidence = new ArrayList<>(edge.evidence());
        evidence.addAll(RuntimeJoinSupport.evidence(aggregate));
        Set<LayerId> provenance = EnumSet.noneOf(LayerId.class);
        evidence.forEach(item -> provenance.add(item.layer()));
        List<String> rules = new ArrayList<>(edge.rules());
        if (!rules.contains(R06RuntimeFloor.ID)) {
            rules.add(R06RuntimeFloor.ID);
        }
        List<Double> weights = new ArrayList<>();
        weights.add(edge.confidence());
        aggregate.facts().forEach(ignored -> weights.add(R06RuntimeFloor.RUNTIME_WEIGHT));
        Set<CanonicalId> candidates = new LinkedHashSet<>(edge.candidates());
        candidates.add(edge.to());
        candidates.add(pair.callee());
        DispatchKind dispatch = RuntimeJoinSupport.dispatch(aggregate, edge.dispatch());
        return new Edge.Resolved(
                EdgeIds.canonical(EdgeKind.CALLS, edge.from(), Optional.of(pair.callee()), edge.site()),
                EdgeKind.CALLS, edge.from(), pair.callee(), edge.site(), provenance,
                Confidence.noisyOr(weights), rules, evidence, dispatch, List.copyOf(candidates),
                EdgeStatus.CONFIRMED);
    }

    private static Edge.Resolved phantom(List<Edge> current,
                                         RuntimeJoinSupport.RuntimeAggregate aggregate) {
        RuntimePair pair = aggregate.pair();
        DispatchKind dispatch = RuntimeJoinSupport.dispatch(aggregate, DispatchKind.UNKNOWN);
        Optional<SiteId> site = nearestUnresolved(current, pair, dispatch);
        List<Evidence.Runtime> evidence = RuntimeJoinSupport.evidence(aggregate);
        List<Double> weights = aggregate.facts().stream().map(ignored -> R06RuntimeFloor.RUNTIME_WEIGHT).toList();
        return new Edge.Resolved(
                EdgeIds.canonical(EdgeKind.PHANTOM, pair.caller(), Optional.of(pair.callee()), site),
                EdgeKind.PHANTOM, pair.caller(), pair.callee(), site, Set.of(LayerId.RUNTIME),
                Confidence.noisyOr(weights), List.of(ID), new ArrayList<>(evidence), dispatch,
                List.of(), EdgeStatus.PHANTOM);
    }

    private static Optional<SiteId> nearestUnresolved(List<Edge> edges, RuntimePair pair, DispatchKind dispatch) {
        if (dispatch == DispatchKind.UNKNOWN) {
            return Optional.empty();
        }
        return edges.stream().filter(Edge.Unresolved.class::isInstance).map(Edge.Unresolved.class::cast)
                .filter(edge -> edge.unresolvedSite().method().equals(pair.caller()))
                .filter(edge -> edge.dispatch() == dispatch)
                .map(Edge.Unresolved::unresolvedSite)
                .min(Comparator.comparingInt(SiteId::bytecodeOffset));
    }

    private static Optional<MethodId> caller(Edge edge) {
        if (edge.from() instanceof MethodId method) {
            return Optional.of(method);
        }
        if (edge.from() instanceof SiteId site) {
            return Optional.of(site.method());
        }
        return Optional.empty();
    }

    private static Map<BeanId, TypeId> beanTypes(JoinContext context) {
        Map<BeanId, TypeId> types = new LinkedHashMap<>();
        context.edgeFacts().stream().filter(entry -> entry.fact() instanceof WiringBindFact)
                .map(entry -> (WiringBindFact) entry.fact()).forEach(bind -> {
                    if (bind.from() instanceof SymbolId.Canonical source && source.id() instanceof BeanId bean
                            && bind.target() instanceof SymbolId.Canonical target
                            && target.id() instanceof TypeId type) {
                        types.put(bean, type);
                    }
                });
        return Map.copyOf(types);
    }
}
