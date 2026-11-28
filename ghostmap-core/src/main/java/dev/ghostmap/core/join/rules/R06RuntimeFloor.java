package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinContext;
import dev.ghostmap.core.join.JoinRule;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.core.layer.runtime.RuntimePair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** R06: add runtime evidence and a 0.99 confidence floor to one matching static edge. */
public final class R06RuntimeFloor implements JoinRule {
    public static final String ID = "R06";
    public static final double RUNTIME_WEIGHT = 0.99;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(JoinContext context) {
        List<JoinedEdge> edges = new ArrayList<>(context.edges());
        for (RuntimeJoinSupport.RuntimeAggregate aggregate : RuntimeJoinSupport.aggregates(context)) {
            RuntimePair pair = aggregate.pair();
            Integer match = matchingEdge(edges, pair);
            if (match == null) {
                continue;
            }
            JoinedEdge edge = edges.get(match);
            JoinedEdge confirmed = edge.withRuntimeEvidence(RuntimeJoinSupport.evidence(aggregate),
                            RuntimeJoinSupport.addContributions(edge.contributions(), aggregate),
                            RuntimeJoinSupport.dispatch(aggregate, edge.dispatch()))
                    .withRule(ID);
            edges.set(match, confirmed);
            context.markRuntimeConsumed(pair);
        }
        context.setEdges(edges);
    }

    private static Integer matchingEdge(List<JoinedEdge> edges, RuntimePair pair) {
        return java.util.stream.IntStream.range(0, edges.size()).boxed()
                .filter(index -> RuntimeJoinSupport.canonicalCaller(edges.get(index).from(), pair))
                .filter(index -> edges.get(index).target().filter(pair.callee()::equals).isPresent())
                .sorted(Comparator.<Integer>comparingDouble(index -> edges.get(index).confidence()).reversed()
                        .thenComparing(index -> edges.get(index).id().value()))
                .findFirst().orElse(null);
    }
}
