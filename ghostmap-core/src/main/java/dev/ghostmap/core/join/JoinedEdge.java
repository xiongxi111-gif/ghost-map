package dev.ghostmap.core.join;

import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.core.layer.bytecode.BytecodeCallFact;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.core.layer.source.SourceCallFact;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.Confidence;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.EdgeId;
import dev.ghostmap.model.EdgeIds;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.LayerId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Scored call edge in the pre-snapshot joined graph. */
public record JoinedEdge(SymbolId from, Optional<CanonicalId> target, Optional<SiteId> bytecodeSite,
                         Optional<SourceCallFact> sourceFact, Optional<BytecodeCallFact> bytecodeFact,
                         List<Evidence> supplementaryEvidence,
                         List<EvidenceContribution> contributions, List<String> rules,
                         DispatchKind dispatch, EdgeStatus status, List<CanonicalId> candidates,
                         boolean staleBuild) {
    public JoinedEdge(SymbolId from, Optional<CanonicalId> target, Optional<SiteId> bytecodeSite,
                      Optional<SourceCallFact> sourceFact, Optional<BytecodeCallFact> bytecodeFact,
                      List<EvidenceContribution> contributions, List<String> rules,
                      DispatchKind dispatch, EdgeStatus status, List<CanonicalId> candidates,
                      boolean staleBuild) {
        this(from, target, bytecodeSite, sourceFact, bytecodeFact, List.of(), contributions, rules,
                dispatch, status, candidates, staleBuild);
    }

    public JoinedEdge {
        if (from == null || target == null || bytecodeSite == null || sourceFact == null || bytecodeFact == null
                || supplementaryEvidence == null || contributions == null || rules == null || dispatch == null || status == null
                || candidates == null) {
            throw new NullPointerException("joined edge fields must not be null");
        }
        supplementaryEvidence = List.copyOf(supplementaryEvidence);
        contributions = List.copyOf(contributions);
        rules = List.copyOf(rules);
        candidates = List.copyOf(candidates);
        if (sourceFact.isEmpty() && bytecodeFact.isEmpty()) {
            throw new IllegalArgumentException("joined edge must retain source or bytecode evidence");
        }
    }

    public EdgeKind kind() {
        return EdgeKind.CALLS;
    }

    public List<Evidence> evidence() {
        List<Evidence> evidence = new ArrayList<>(2);
        sourceFact.map(SourceCallFact::evidence).ifPresent(evidence::add);
        bytecodeFact.map(BytecodeCallFact::evidence).ifPresent(evidence::add);
        evidence.addAll(supplementaryEvidence);
        return List.copyOf(evidence);
    }

    public Set<LayerId> provenance() {
        Set<LayerId> layers = EnumSet.noneOf(LayerId.class);
        evidence().forEach(item -> layers.add(item.layer()));
        return Set.copyOf(layers);
    }

    public double confidence() {
        return Confidence.noisyOr(contributions.stream().map(EvidenceContribution::weight).toList());
    }

    public EdgeId id() {
        if (from instanceof SymbolId.Canonical canonical && target.isPresent()) {
            return EdgeIds.canonical(EdgeKind.CALLS, canonical.id(), target, bytecodeSite);
        }
        String sourceSite = sourceFact.map(fact -> fact.site().value()).orElse("");
        String input = "CALLS|" + from.value() + '|' + target.map(CanonicalId::value).orElse("") + '|'
                + bytecodeSite.map(CanonicalId::value).orElse(sourceSite);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return new EdgeId("sha256:" + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }

    public Optional<JvmInvocationKind> invocationKind() {
        return bytecodeFact.map(BytecodeCallFact::invocationKind);
    }

    public Optional<Edge> modelEdge() {
        if (!(from instanceof SymbolId.Canonical canonical) || target.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Edge.Resolved(
                EdgeIds.canonical(EdgeKind.CALLS, canonical.id(), target, bytecodeSite),
                EdgeKind.CALLS,
                canonical.id(),
                target.orElseThrow(),
                bytecodeSite,
                provenance(),
                confidence(),
                rules,
                evidence(),
                dispatch,
                candidates,
                status));
    }

    public JoinedEdge withRule(String rule) {
        if (rules.contains(rule)) {
            return this;
        }
        List<String> updated = new ArrayList<>(rules);
        updated.add(rule);
        return copy(target, bytecodeSite, contributions, updated, dispatch, status, candidates, staleBuild);
    }

    public JoinedEdge withTarget(CanonicalId updatedTarget, List<CanonicalId> updatedCandidates,
                                 List<EvidenceContribution> updatedContributions, EdgeStatus updatedStatus) {
        return copy(Optional.of(updatedTarget), bytecodeSite, updatedContributions, rules, dispatch,
                updatedStatus, updatedCandidates, staleBuild);
    }

    public JoinedEdge withContributions(List<EvidenceContribution> updatedContributions, boolean stale) {
        return copy(target, bytecodeSite, updatedContributions, rules, dispatch, status, candidates, stale);
    }

    public JoinedEdge withSupplementaryEvidence(Evidence evidence,
                                                List<EvidenceContribution> updatedContributions,
                                                CanonicalId updatedTarget, EdgeStatus updatedStatus) {
        List<Evidence> updatedEvidence = new ArrayList<>(supplementaryEvidence);
        updatedEvidence.add(evidence);
        return new JoinedEdge(from, Optional.of(updatedTarget), bytecodeSite, sourceFact, bytecodeFact,
                updatedEvidence, updatedContributions, rules, dispatch, updatedStatus,
                List.of(updatedTarget), staleBuild);
    }

    public JoinedEdge withRuntimeEvidence(List<Evidence.Runtime> runtimeEvidence,
                                          List<EvidenceContribution> updatedContributions,
                                          DispatchKind updatedDispatch) {
        List<Evidence> updatedEvidence = new ArrayList<>(supplementaryEvidence);
        updatedEvidence.addAll(runtimeEvidence);
        return new JoinedEdge(from, target, bytecodeSite, sourceFact, bytecodeFact, updatedEvidence,
                updatedContributions, rules, updatedDispatch, EdgeStatus.CONFIRMED, candidates, staleBuild);
    }

    private JoinedEdge copy(Optional<CanonicalId> updatedTarget, Optional<SiteId> updatedSite,
                            List<EvidenceContribution> updatedContributions, List<String> updatedRules,
                            DispatchKind updatedDispatch, EdgeStatus updatedStatus,
                            List<CanonicalId> updatedCandidates, boolean updatedStaleBuild) {
        return new JoinedEdge(from, updatedTarget, updatedSite, sourceFact, bytecodeFact, supplementaryEvidence,
                updatedContributions, updatedRules, updatedDispatch, updatedStatus, updatedCandidates,
                updatedStaleBuild);
    }
}
