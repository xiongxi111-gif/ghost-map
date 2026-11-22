package dev.ghostmap.model;

import java.util.List;

/** Workload metadata for one runtime evidence run. */
public record RuntimeRun(String runId, String workload, List<String> filter) {
    public RuntimeRun {
        ModelChecks.nonBlank(runId, "runId");
        ModelChecks.nonBlank(workload, "workload");
        filter = ModelChecks.immutableList(filter, "filter");
        filter.forEach(value -> ModelChecks.nonBlank(value, "filter value"));
    }
}
