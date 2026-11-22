package dev.ghostmap.mcp;

import dev.ghostmap.model.Snapshot;
import dev.ghostmap.model.SnapshotJson;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.transport.StdioMcpTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runnable, read-only Ghost Map MCP server using newline-delimited stdio. */
public final class GhostMapMcpServer {
    static final String VERSION = "0.1.0";

    private GhostMapMcpServer() {
    }

    public static void main(String[] args) {
        try {
            Path snapshotPath = snapshotPath(args);
            Snapshot snapshot = loadSnapshot(snapshotPath);
            McpJsonMapper mapper = McpJsonDefaults.getMapper();
            McpServer server = createServer(snapshot, mapper);
            StdioMcpTransport transport = new StdioMcpTransport(server, mapper);
            try {
                transport.start().block();
            } finally {
                server.closeGracefully();
                transport.closeGracefully().block();
            }
        } catch (IllegalArgumentException | IOException exception) {
            System.err.println("ghostmap-mcp: " + exception.getMessage());
            System.err.println("usage: java -jar ghostmap-mcp-all.jar --snapshot <snapshot.ghostmap.json>");
            System.exit(2);
        }
    }

    static McpServer createServer(Snapshot snapshot, McpJsonMapper mapper) {
        GhostMapToolRepository tools = new GhostMapToolRepository(new SnapshotIndex(snapshot));
        return McpServer.builder()
                .serverInfo(McpSchema.Implementation.builder("ghostmap-mcp", VERSION).build())
                .instructions("Read-only evidence-graded queries over the loaded Ghost Map snapshot.")
                .jsonMapper(mapper)
                .feature(ToolsFeature.ofSync(tools))
                .build();
    }

    static Snapshot loadSnapshot(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("snapshot is not a regular file: " + path);
        }
        String json = Files.readString(path, StandardCharsets.UTF_8);
        try {
            return SnapshotJson.fromJson(json);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid snapshot " + path + ": " + exception.getMessage(), exception);
        }
    }

    static Path snapshotPath(String[] args) {
        if (args == null || args.length != 2 || !"--snapshot".equals(args[0]) || args[1].isBlank()) {
            throw new IllegalArgumentException("exactly --snapshot <path> is required");
        }
        return Path.of(args[1]);
    }
}
