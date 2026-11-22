package dev.ghostmap.core.join;

import dev.ghostmap.core.join.rules.R01Identity;
import dev.ghostmap.core.join.rules.R02CallMatch;
import dev.ghostmap.core.join.rules.R03GeneratedMembers;
import dev.ghostmap.core.join.rules.R04VirtualExpansion;
import dev.ghostmap.core.join.rules.R05WiringNarrowing;
import dev.ghostmap.core.join.rules.R07DynamicSites;
import dev.ghostmap.core.join.rules.R09Conflicts;
import dev.ghostmap.core.layer.LayerFacts;

import java.util.Arrays;
import java.util.List;

/** Fixed-order static join policy; runtime rules remain unimplemented. */
public final class JoinPolicy {
    public static final String VERSION = "jp/v1";

    public JoinedGraph join(LayerFacts... facts) {
        if (facts == null) {
            throw new NullPointerException("facts");
        }
        return join(Arrays.asList(facts));
    }

    public JoinedGraph join(List<LayerFacts> facts) {
        JoinContext context = new JoinContext(facts);
        for (JoinRule rule : rules()) {
            rule.apply(context);
        }
        return context.result();
    }

    public List<String> ruleIds() {
        return rules().stream().map(JoinRule::id).toList();
    }

    private static List<JoinRule> rules() {
        return List.of(
                new R01Identity(),
                new R02CallMatch(),
                new R03GeneratedMembers(),
                new R04VirtualExpansion(),
                new R05WiringNarrowing(),
                new R07DynamicSites(),
                new R09Conflicts());
    }
}
