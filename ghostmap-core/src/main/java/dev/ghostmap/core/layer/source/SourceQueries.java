package dev.ghostmap.core.layer.source;

import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSLanguage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SourceQueries implements AutoCloseable {
    private final Map<String, TSQuery> queries;

    SourceQueries(TSLanguage language) {
        queries = Map.of(
                "declarations", query(language, "queries/declarations.scm"),
                "invocations", query(language, "queries/invocations.scm"),
                "annotations", query(language, "queries/annotations.scm"),
                "compilation-unit", query(language, "queries/compilation-unit.scm"),
                "variables", query(language, "queries/variables.scm"));
    }

    List<TSNode> nodes(String queryName, String captureName, TSNode root, CharSequence source) {
        TSQuery query = queries.get(queryName);
        if (query == null) {
            throw new IllegalArgumentException("unknown source query: " + queryName);
        }
        Map<NodeKey, TSNode> found = new LinkedHashMap<>();
        try (TSQueryCursor cursor = new TSQueryCursor()) {
            cursor.exec(query, root, source);
            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                for (TSQueryCapture capture : match.getCaptures()) {
                    if (captureName.equals(query.getCaptureNameForId(capture.getIndex()))) {
                        TSNode node = capture.getNode();
                        found.putIfAbsent(NodeKey.of(node), node);
                    }
                }
            }
        }
        return found.values().stream()
                .sorted(Comparator.comparingInt(TSNode::getStartByte).thenComparingInt(TSNode::getEndByte))
                .toList();
    }

    @Override
    public void close() {
        List<TSQuery> values = new ArrayList<>(queries.values());
        for (TSQuery query : values) {
            query.close();
        }
    }

    private static TSQuery query(TSLanguage language, String resource) {
        return new TSQuery(language, load(resource));
    }

    private static String load(String resource) {
        ClassLoader loader = SourceQueries.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing tree-sitter query resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read tree-sitter query resource: " + resource, exception);
        }
    }

    record NodeKey(String type, int startByte, int endByte) {
        static NodeKey of(TSNode node) {
            return new NodeKey(node.getType(), node.getStartByte(), node.getEndByte());
        }
    }
}
