package dev.ghostmap.model;

/** Tool and join-policy versions that produced a snapshot. */
public record ToolInfo(String name, String version, String joinPolicy) {
    public ToolInfo {
        ModelChecks.nonBlank(name, "name");
        ModelChecks.nonBlank(version, "version");
        ModelChecks.nonBlank(joinPolicy, "joinPolicy");
    }
}
