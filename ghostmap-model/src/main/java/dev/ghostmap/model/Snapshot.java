package dev.ghostmap.model;

import java.util.List;
import java.util.Set;

/** Versioned, evidence-graded graph snapshot. */
public record Snapshot(String schema, String snapshotId, CodeVersion codeVersion, ToolInfo tool,
                       Set<LayerId> layersRun, List<RuntimeRun> runtimeRuns,
                       List<Node> nodes, List<Edge> edges, BlindSpotReport report) {
    public static final String SCHEMA = "ghostmap.snapshot/v1";

    public Snapshot {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported snapshot schema: " + schema);
        }
        CodeVersion.requireSha256(snapshotId, "snapshotId");
        if (codeVersion == null || tool == null || report == null) {
            throw new NullPointerException("snapshot metadata must not be null");
        }
        layersRun = ModelChecks.immutableSet(layersRun, "layersRun");
        runtimeRuns = ModelChecks.immutableList(runtimeRuns, "runtimeRuns");
        nodes = ModelChecks.immutableList(nodes, "nodes");
        edges = ModelChecks.immutableList(edges, "edges");
    }
}
