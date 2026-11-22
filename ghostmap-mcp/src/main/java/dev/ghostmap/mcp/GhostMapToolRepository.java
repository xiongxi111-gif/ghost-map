package dev.ghostmap.mcp;

import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;

import java.util.List;

/** Fixed read-only tool catalogue for the Task 11 MCP milestone. */
final class GhostMapToolRepository implements McpSyncToolRepository {
    private final McpSyncTool whoInjects;

    GhostMapToolRepository(SnapshotIndex index) {
        this.whoInjects = new WhoInjectsTool(index).specification();
    }

    @Override
    public ToolsPage list(McpRequestContext context, String cursor) {
        if (cursor != null && !cursor.isBlank()) {
            return ToolsPage.of(List.of());
        }
        return ToolsPage.of(List.of(whoInjects.definition()));
    }

    @Override
    public McpSyncTool find(McpRequestContext context, String name) {
        return WhoInjectsTool.NAME.equals(name) ? whoInjects : null;
    }
}
