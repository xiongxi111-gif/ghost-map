package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinContext;
import dev.ghostmap.core.join.JoinRule;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.Node;
import dev.ghostmap.model.NodeKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** R03: retain and label bytecode members that have no source declaration. */
public final class R03GeneratedMembers implements JoinRule {
    public static final String ID = "R03";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(JoinContext context) {
        Set<CanonicalId> sourceIds = new HashSet<>();
        Set<CanonicalId> bytecodeIds = new HashSet<>();
        context.nodeFacts().forEach(fact -> context.resolve(fact.node().id()).ifPresent(id -> {
            if (fact.layer() == LayerId.AST) {
                sourceIds.add(id);
            } else if (fact.layer() == LayerId.BYTECODE) {
                bytecodeIds.add(id);
            }
        }));
        boolean compareLayers = context.hasLayer(LayerId.AST) && context.hasLayer(LayerId.BYTECODE);
        sourceIds.addAll(context.potentialSourceIdentities());
        Set<CanonicalId> generatedIds = new HashSet<>();
        List<Node> updatedNodes = context.nodes().stream().map(node -> {
            boolean bytecodeOnlyMember = compareLayers && bytecodeIds.contains(node.id())
                    && !sourceIds.contains(node.id())
                    && (node.kind() == NodeKind.METHOD || node.kind() == NodeKind.FIELD);
            boolean generated = generated(node) || bytecodeOnlyMember;
            if (!generated) {
                return node;
            }
            generatedIds.add(node.id());
            Map<String, JsonValue> attrs = new LinkedHashMap<>(node.attrs());
            attrs.put("generated", new BooleanValue(true));
            Set<String> reasons = new LinkedHashSet<>(strings(node.attrs().get("generationReasons")));
            if (bytecodeOnlyMember) {
                reasons.add("NO_SOURCE_DECLARATION");
            }
            attrs.put("generationReasons", array(reasons));
            Set<String> rules = new LinkedHashSet<>(strings(node.attrs().get("joinRules")));
            rules.add(ID);
            attrs.put("joinRules", array(rules));
            return new Node(node.id(), node.kind(), node.name(), node.owner(), attrs, node.location());
        }).toList();
        context.setNodes(updatedNodes);

        List<JoinedEdge> updatedEdges = context.edges().stream()
                .map(edge -> edge.target().filter(generatedIds::contains).isPresent() ? edge.withRule(ID) : edge)
                .toList();
        context.setEdges(updatedEdges);
    }

    private static boolean generated(Node node) {
        return node.attrs().get("generated") instanceof BooleanValue value && value.value();
    }

    private static List<String> strings(JsonValue value) {
        if (!(value instanceof ArrayValue array)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        array.values().stream().filter(StringValue.class::isInstance).map(StringValue.class::cast)
                .map(StringValue::value).forEach(strings::add);
        return strings;
    }

    private static ArrayValue array(Set<String> values) {
        return new ArrayValue(values.stream().sorted().map(value -> (JsonValue) new StringValue(value)).toList());
    }
}
