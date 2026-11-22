package dev.ghostmap.core.layer.source;

import dev.ghostmap.model.CanonicalId.TypeId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class SourceTypeResolver {
    private static final Map<String, String> PRIMITIVES = Map.of(
            "byte", "B",
            "char", "C",
            "double", "D",
            "float", "F",
            "int", "I",
            "long", "J",
            "short", "S",
            "boolean", "Z",
            "void", "V");
    private static final Set<String> JAVA_LANG_TYPES = Set.of(
            "Boolean", "Byte", "Character", "Class", "Double", "Enum", "Error", "Exception",
            "Float", "IllegalArgumentException", "IllegalStateException", "Integer", "Iterable", "Long",
            "Number", "Object", "Record", "ReflectiveOperationException", "RuntimeException", "Short",
            "String", "StringBuilder", "Throwable", "Void");

    private final TypeIndex typeIndex;
    private final String packageName;
    private final Map<String, String> explicitImports;

    SourceTypeResolver(TypeIndex typeIndex, String packageName, Map<String, String> explicitImports) {
        this.typeIndex = typeIndex;
        this.packageName = packageName;
        this.explicitImports = Map.copyOf(explicitImports);
    }

    Optional<ResolvedType> resolve(String sourceType) {
        if (sourceType == null) {
            return Optional.empty();
        }
        String type = erase(sourceType);
        if (type.isEmpty() || type.startsWith("?") || "var".equals(type)) {
            return Optional.empty();
        }
        int dimensions = 0;
        while (type.endsWith("[]")) {
            dimensions++;
            type = type.substring(0, type.length() - 2).strip();
        }
        if (type.endsWith("...")) {
            dimensions++;
            type = type.substring(0, type.length() - 3).strip();
        }

        String primitive = PRIMITIVES.get(type);
        if (primitive != null) {
            if ("V".equals(primitive) && dimensions > 0) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedType("[".repeat(dimensions) + primitive, Optional.empty()));
        }

        Optional<String> binaryName = resolveBinaryName(type);
        if (binaryName.isEmpty()) {
            return Optional.empty();
        }
        TypeId typeId = new TypeId(binaryName.orElseThrow());
        String descriptor = "[".repeat(dimensions) + 'L' + typeId.binaryName().replace('.', '/') + ';';
        return Optional.of(new ResolvedType(descriptor, Optional.of(typeId)));
    }

    private Optional<String> resolveBinaryName(String type) {
        Optional<String> indexed = typeIndex.binaryForSourceName(type);
        if (indexed.isPresent()) {
            return indexed;
        }
        if (!packageName.isEmpty()) {
            indexed = typeIndex.binaryForSourceName(packageName + '.' + type);
            if (indexed.isPresent()) {
                return indexed;
            }
        }

        int dot = type.indexOf('.');
        String first = dot < 0 ? type : type.substring(0, dot);
        String imported = explicitImports.get(first);
        if (imported != null) {
            String suffix = dot < 0 ? "" : type.substring(dot).replace('.', '$');
            return Optional.of(toBinaryName(imported) + suffix);
        }
        if (dot >= 0 && Character.isLowerCase(type.charAt(0))) {
            return Optional.of(toBinaryName(type));
        }
        if (dot < 0 && JAVA_LANG_TYPES.contains(type)) {
            return Optional.of("java.lang." + type);
        }
        return typeIndex.uniqueBinaryForSimpleName(type);
    }

    private static String toBinaryName(String sourceName) {
        String[] segments = sourceName.split("\\.");
        StringBuilder binary = new StringBuilder(sourceName.length());
        boolean inType = false;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return sourceName;
            }
            if (!binary.isEmpty()) {
                binary.append(inType ? '$' : '.');
            }
            binary.append(segment);
            if (!inType && Character.isUpperCase(segment.charAt(0))) {
                inType = true;
            }
        }
        return binary.toString();
    }

    private static String erase(String sourceType) {
        String compact = sourceType.strip();
        StringBuilder erased = new StringBuilder(compact.length());
        int genericDepth = 0;
        for (int index = 0; index < compact.length(); index++) {
            char character = compact.charAt(index);
            if (character == '<') {
                genericDepth++;
            } else if (character == '>') {
                if (genericDepth > 0) {
                    genericDepth--;
                }
            } else if (genericDepth == 0 && !Character.isWhitespace(character)) {
                erased.append(character);
            }
        }
        return genericDepth == 0 ? erased.toString() : "";
    }

    record ResolvedType(String descriptor, Optional<TypeId> typeId) {
        ResolvedType {
            if (descriptor == null || typeId == null) {
                throw new NullPointerException("resolved type fields must not be null");
            }
        }
    }

    static final class TypeIndex {
        private final Map<String, String> sourceNames = new HashMap<>();
        private final Map<String, Set<String>> simpleNames = new HashMap<>();
        private final Set<String> interfaces = new HashSet<>();

        void add(String binaryName, boolean interfaceType) {
            String sourceName = binaryName.replace('$', '.');
            sourceNames.put(sourceName, binaryName);
            String simpleName = binaryName.substring(Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$')) + 1);
            simpleNames.computeIfAbsent(simpleName, ignored -> new HashSet<>()).add(binaryName);
            if (interfaceType) {
                interfaces.add(binaryName);
            }
        }

        Optional<String> binaryForSourceName(String sourceName) {
            return Optional.ofNullable(sourceNames.get(sourceName));
        }

        Optional<String> uniqueBinaryForSimpleName(String simpleName) {
            Set<String> candidates = simpleNames.get(simpleName);
            if (candidates == null || candidates.size() != 1) {
                return Optional.empty();
            }
            return Optional.of(candidates.iterator().next());
        }

        boolean isInterface(TypeId type) {
            return interfaces.contains(type.binaryName());
        }
    }
}
