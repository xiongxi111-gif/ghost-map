package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.EvidenceContribution;
import dev.ghostmap.core.join.JoinContext;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.core.layer.runtime.RuntimeCallFact;
import dev.ghostmap.core.layer.runtime.RuntimePair;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.LayerId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared deterministic runtime aggregation used by R06 and R08. */
final class RuntimeJoinSupport {
    private RuntimeJoinSupport() {
    }

    static List<RuntimeAggregate> aggregates(JoinContext context) {
        Map<RuntimePair, List<RuntimeCallFact>> grouped = new LinkedHashMap<>();
        context.edgeFacts().stream().filter(entry -> entry.fact() instanceof RuntimeCallFact)
                .map(entry -> (RuntimeCallFact) entry.fact())
                .sorted(Comparator.comparing((RuntimeCallFact fact) -> fact.caller().value())
                        .thenComparing(fact -> fact.callee().value())
                        .thenComparing(RuntimeCallFact::runId))
                .forEach(fact -> grouped.computeIfAbsent(new RuntimePair(fact.caller(), fact.callee()),
                        ignored -> new ArrayList<>()).add(fact));
        return grouped.entrySet().stream().map(entry -> new RuntimeAggregate(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(RuntimeAggregate::pair)).toList();
    }

    static DispatchKind dispatch(RuntimeAggregate aggregate, DispatchKind fallback) {
        Set<DispatchKind> detected = new LinkedHashSet<>();
        aggregate.facts().stream().map(RuntimeCallFact::dispatch)
                .filter(kind -> kind != DispatchKind.UNKNOWN).forEach(detected::add);
        if (detected.isEmpty()) {
            return fallback;
        }
        return detected.size() == 1 ? detected.iterator().next() : DispatchKind.UNKNOWN;
    }

    static List<Evidence.Runtime> evidence(RuntimeAggregate aggregate) {
        return aggregate.facts().stream().map(RuntimeCallFact::evidence).toList();
    }

    static List<EvidenceContribution> addContributions(List<EvidenceContribution> existing,
                                                        RuntimeAggregate aggregate) {
        List<EvidenceContribution> updated = new ArrayList<>(existing);
        aggregate.facts().forEach(fact -> updated.add(new EvidenceContribution(LayerId.RUNTIME,
                R06RuntimeFloor.RUNTIME_WEIGHT, "RUNTIME_OBSERVED")));
        return List.copyOf(updated);
    }

    record RuntimeAggregate(RuntimePair pair, List<RuntimeCallFact> facts) {
        RuntimeAggregate {
            if (pair == null || facts == null || facts.isEmpty()) {
                throw new IllegalArgumentException("runtime aggregate requires a pair and facts");
            }
            facts = List.copyOf(facts);
        }
    }

    static boolean canonicalCaller(SymbolId source, RuntimePair pair) {
        return source instanceof SymbolId.Canonical canonical && canonical.id().equals(pair.caller());
    }
}
