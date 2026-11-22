package dev.ghostmap.model;

import dev.ghostmap.model.CanonicalId.SiteId;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** An immutable resolved or explicitly unresolved graph relationship. */
public sealed interface Edge permits Edge.Resolved, Edge.Unresolved {
    EdgeId id();

    EdgeKind kind();

    CanonicalId from();

    Optional<CanonicalId> target();

    Optional<SiteId> site();

    Set<LayerId> provenance();

    double confidence();

    List<String> rules();

    List<Evidence> evidence();

    DispatchKind dispatch();

    List<CanonicalId> candidates();

    EdgeStatus status();

    record Resolved(EdgeId id, EdgeKind kind, CanonicalId from, CanonicalId to, Optional<SiteId> site,
                    Set<LayerId> provenance, double confidence, List<String> rules, List<Evidence> evidence,
                    DispatchKind dispatch, List<CanonicalId> candidates, EdgeStatus status) implements Edge {
        public Resolved {
            if (id == null || kind == null || from == null || to == null || site == null || dispatch == null
                    || status == null) {
                throw new NullPointerException("resolved edge fields must not be null");
            }
            if (kind == EdgeKind.UNRESOLVED || status == EdgeStatus.UNRESOLVED) {
                throw new IllegalArgumentException("resolved edges cannot have unresolved kind or status");
            }
            provenance = ModelChecks.immutableSet(provenance, "provenance");
            confidence = ModelChecks.confidence(confidence);
            rules = nonBlankRules(rules);
            evidence = checkedEvidence(evidence, provenance);
            candidates = ModelChecks.immutableList(candidates, "candidates");
            EdgeId expected = EdgeIds.canonical(kind, from, Optional.of(to), site);
            if (!id.equals(expected)) {
                throw new IllegalArgumentException("edge id is not canonical");
            }
        }

        @Override
        public Optional<CanonicalId> target() {
            return Optional.of(to);
        }
    }

    record Unresolved(EdgeId id, CanonicalId from, SiteId unresolvedSite, Set<LayerId> provenance,
                      double confidence, List<String> rules, List<Evidence> evidence, DispatchKind dispatch,
                      List<CanonicalId> candidates, Optional<String> partialKey) implements Edge {
        public Unresolved {
            if (id == null || from == null || unresolvedSite == null || dispatch == null || partialKey == null) {
                throw new NullPointerException("unresolved edge fields must not be null");
            }
            if (!from.equals(unresolvedSite)) {
                throw new IllegalArgumentException("unresolved edges must originate at their site node");
            }
            provenance = ModelChecks.immutableSet(provenance, "provenance");
            confidence = ModelChecks.confidence(confidence);
            rules = nonBlankRules(rules);
            evidence = checkedEvidence(evidence, provenance);
            candidates = ModelChecks.immutableList(candidates, "candidates");
            partialKey.ifPresent(key -> ModelChecks.nonBlank(key, "partialKey"));
            EdgeId expected = EdgeIds.canonical(EdgeKind.UNRESOLVED, from, Optional.empty(),
                    Optional.of(unresolvedSite));
            if (!id.equals(expected)) {
                throw new IllegalArgumentException("edge id is not canonical");
            }
        }

        @Override
        public EdgeKind kind() {
            return EdgeKind.UNRESOLVED;
        }

        @Override
        public Optional<CanonicalId> target() {
            return Optional.empty();
        }

        @Override
        public Optional<SiteId> site() {
            return Optional.of(unresolvedSite);
        }

        @Override
        public EdgeStatus status() {
            return EdgeStatus.UNRESOLVED;
        }
    }

    private static List<String> nonBlankRules(List<String> rules) {
        List<String> copy = ModelChecks.immutableList(rules, "rules");
        copy.forEach(rule -> ModelChecks.nonBlank(rule, "rule id"));
        return copy;
    }

    private static List<Evidence> checkedEvidence(List<Evidence> evidence, Set<LayerId> provenance) {
        List<Evidence> copy = ModelChecks.immutableList(evidence, "evidence");
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("every edge must carry evidence");
        }
        Set<LayerId> evidenceLayers = EnumSet.noneOf(LayerId.class);
        copy.forEach(item -> evidenceLayers.add(item.layer()));
        if (!evidenceLayers.equals(provenance)) {
            throw new IllegalArgumentException("provenance must equal the evidence layers");
        }
        return copy;
    }
}
