package dev.ghostmap.model;

import java.util.Map;

/** Counts and measured coverage that prevent silent graph blind spots. */
public record BlindSpotReport(Map<String, Long> edgesByProvenance,
                              Map<UnresolvedSiteKind, Long> unresolvedSites,
                              long phantomEdges,
                              ExercisedCoverage exercised,
                              StaticRecall staticRecallOnExercised,
                              String caveat) {
    public BlindSpotReport {
        edgesByProvenance = ModelChecks.immutableMap(edgesByProvenance, "edgesByProvenance");
        edgesByProvenance.forEach((key, value) -> {
            ModelChecks.nonBlank(key, "provenance key");
            ModelChecks.nonNegative(value, "provenance count");
        });
        unresolvedSites = ModelChecks.immutableMap(unresolvedSites, "unresolvedSites");
        unresolvedSites.values().forEach(value -> ModelChecks.nonNegative(value, "unresolved site count"));
        ModelChecks.nonNegative(phantomEdges, "phantomEdges");
        if (exercised == null || staticRecallOnExercised == null) {
            throw new NullPointerException("report coverage fields must not be null");
        }
        ModelChecks.nonBlank(caveat, "caveat");
    }
}
