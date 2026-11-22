package dev.ghostmap.core.layer.source;

import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.DiagnosticSeverity;
import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.Layer;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.ProvisionalId;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId.FieldId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.NumberValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Tree-sitter Java source analysis used as the AST-only baseline. */
public final class SourceLayer implements Layer {
    @Override
    public LayerId id() {
        return LayerId.AST;
    }

    @Override
    public LayerFacts analyse(LayerInput input) {
        if (input == null) {
            throw new NullPointerException("input");
        }
        Path root = input.requireSourceRoot();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (!Files.exists(root)) {
            diagnostics.add(rootDiagnostic(DiagnosticCode.SOURCE_ROOT_NOT_FOUND, root, "source root does not exist"));
            return facts(List.of(), List.of(), diagnostics);
        }
        if (!Files.isDirectory(root)) {
            diagnostics.add(rootDiagnostic(DiagnosticCode.SOURCE_ROOT_NOT_DIRECTORY, root,
                    "source root is not a directory"));
            return facts(List.of(), List.of(), diagnostics);
        }

        List<Path> javaFiles;
        try (Stream<Path> paths = Files.walk(root)) {
            javaFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException | UncheckedIOException exception) {
            diagnostics.add(rootDiagnostic(DiagnosticCode.SOURCE_ROOT_READ_ERROR, root,
                    "cannot enumerate source root: " + exception.getMessage()));
            return facts(List.of(), List.of(), diagnostics);
        }
        if (javaFiles.isEmpty()) {
            return facts(List.of(), List.of(), diagnostics);
        }

        List<ParsedUnit> units = new ArrayList<>();
        List<FactNode> nodes = new ArrayList<>();
        List<EdgeFact> edges = new ArrayList<>();
        try (TreeSitterJava language = new TreeSitterJava();
             TSParser parser = new TSParser();
             SourceQueries queries = new SourceQueries(language)) {
            try {
                if (!parser.setLanguage(language)) {
                    throw new IllegalStateException("tree-sitter rejected the Java grammar");
                }
                for (Path file : javaFiles) {
                    parseFile(root, file, parser, diagnostics).ifPresent(units::add);
                }
                SourceTypeResolver.TypeIndex typeIndex = new SourceTypeResolver.TypeIndex();
                List<ParsedUnit> discovered = new ArrayList<>();
                for (ParsedUnit unit : units) {
                    try {
                        discoverTypes(unit, queries, typeIndex);
                        discovered.add(unit);
                    } catch (RuntimeException exception) {
                        diagnostics.add(unitDiagnostic(unit, "cannot index source declarations", exception));
                    }
                }
                for (ParsedUnit unit : discovered) {
                    try {
                        emitUnit(unit, queries, typeIndex, nodes, edges);
                    } catch (RuntimeException exception) {
                        diagnostics.add(unitDiagnostic(unit, "cannot extract source facts", exception));
                    }
                }
            } finally {
                units.forEach(ParsedUnit::close);
            }
        }
        return facts(nodes, edges, diagnostics);
    }

    private static Optional<ParsedUnit> parseFile(Path root, Path file, TSParser parser,
                                                   List<Diagnostic> diagnostics) {
        String display = portable(root.relativize(file));
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            diagnostics.add(new Diagnostic(DiagnosticCode.SOURCE_FILE_READ_ERROR, DiagnosticSeverity.ERROR,
                    display, 0, "cannot read source file: " + exception.getMessage()));
            return Optional.empty();
        }
        try {
            TSTree tree = parser.parseString(null, source);
            parser.reset();
            if (tree == null) {
                diagnostics.add(new Diagnostic(DiagnosticCode.PARSE_ERROR, DiagnosticSeverity.ERROR,
                        display, 0, "tree-sitter returned no syntax tree"));
                return Optional.empty();
            }
            TSNode rootNode = tree.getRootNode();
            if (rootNode.hasError()) {
                TSNode error = firstError(rootNode).orElse(rootNode);
                diagnostics.add(new Diagnostic(DiagnosticCode.PARSE_ERROR, DiagnosticSeverity.ERROR,
                        display, line(error.getStartPoint()), "tree-sitter recovered from a Java syntax error"));
            }
            return Optional.of(new ParsedUnit(display, source, tree, rootNode));
        } catch (RuntimeException exception) {
            parser.reset();
            diagnostics.add(new Diagnostic(DiagnosticCode.PARSE_ERROR, DiagnosticSeverity.ERROR,
                    display, 0, "tree-sitter could not parse file: " + exception.getMessage()));
            return Optional.empty();
        }
    }

    private static void discoverTypes(ParsedUnit unit, SourceQueries queries,
                                      SourceTypeResolver.TypeIndex typeIndex) {
        unit.readCompilationUnit(queries);
        List<TSNode> declarations = queries.nodes("declarations", "type.declaration", unit.root(), unit.source());
        for (TSNode declaration : declarations) {
            Optional<TSNode> nameNode = child(declaration, "name");
            if (nameNode.isEmpty()) {
                continue;
            }
            String simpleName = unit.text(nameNode.orElseThrow());
            Optional<TypeDecl> outer = nearest(declaration.getParent(), unit.types());
            String binaryName = outer.map(type -> type.id().binaryName() + '$' + simpleName)
                    .orElseGet(() -> unit.packageName().isEmpty() ? simpleName
                            : unit.packageName() + '.' + simpleName);
            boolean interfaceType = "interface_declaration".equals(declaration.getType())
                    || "annotation_type_declaration".equals(declaration.getType());
            TypeDecl type = new TypeDecl(declaration, new TypeId(binaryName), simpleName, interfaceType,
                    outer.map(TypeDecl::id));
            unit.types().put(SourceQueries.NodeKey.of(declaration), type);
            typeIndex.add(binaryName, interfaceType);
        }
    }

    private static void emitUnit(ParsedUnit unit, SourceQueries queries, SourceTypeResolver.TypeIndex typeIndex,
                                 List<FactNode> outputNodes, List<EdgeFact> outputEdges) {
        SourceTypeResolver resolver = new SourceTypeResolver(typeIndex, unit.packageName(), unit.imports());
        Map<SourceQueries.NodeKey, List<String>> annotations = annotations(unit, queries);
        Map<TypeId, Map<String, TypeId>> fieldTypes = new HashMap<>();

        for (TypeDecl type : unit.types().values()) {
            Map<String, JsonValue> attrs = attributes(annotations.getOrDefault(SourceQueries.NodeKey.of(type.node()),
                    List.of()), false, Map.of("declaration", new StringValue(type.node().getType())));
            outputNodes.add(new FactNode(canonical(type.id()), NodeKind.TYPE, type.name(), type.outer(), attrs,
                    unit.location(type.node())));
        }

        emitFields(unit, queries, resolver, annotations, fieldTypes, outputNodes);
        Map<SourceQueries.NodeKey, MethodDecl> methods = emitMethods(unit, queries, resolver, annotations,
                fieldTypes, outputNodes);
        populateVariables(unit, queries, resolver, methods);
        emitCalls(unit, queries, resolver, typeIndex, methods, outputEdges);
    }

    private static void emitFields(ParsedUnit unit, SourceQueries queries, SourceTypeResolver resolver,
                                   Map<SourceQueries.NodeKey, List<String>> annotations,
                                   Map<TypeId, Map<String, TypeId>> fieldTypes, List<FactNode> outputNodes) {
        for (TSNode declaration : queries.nodes("declarations", "field.declaration", unit.root(), unit.source())) {
            Optional<TypeDecl> owner = nearest(declaration.getParent(), unit.types());
            Optional<TSNode> typeNode = child(declaration, "type");
            if (owner.isEmpty() || typeNode.isEmpty()) {
                continue;
            }
            String sourceType = unit.text(typeNode.orElseThrow());
            Optional<SourceTypeResolver.ResolvedType> resolved = resolver.resolve(sourceType);
            List<TSNode> declarators = descendantsOfType(declaration, "variable_declarator");
            for (TSNode declarator : declarators) {
                Optional<TSNode> nameNode = child(declarator, "name");
                if (nameNode.isEmpty()) {
                    continue;
                }
                String name = unit.text(nameNode.orElseThrow());
                SourceLocation location = unit.location(declaration);
                SymbolId id = resolved.<SymbolId>map(type -> canonical(new FieldId(owner.orElseThrow().id().binaryName(),
                                name, type.descriptor())))
                        .orElseGet(() -> new ProvisionalId(NodeKind.FIELD, owner.orElseThrow().id(), name, -1,
                                location.line(), location.endLine()));
                Map<String, JsonValue> extras = new LinkedHashMap<>();
                extras.put("declaredType", new StringValue(sourceType));
                Map<String, JsonValue> attrs = attributes(
                        annotations.getOrDefault(SourceQueries.NodeKey.of(declaration), List.of()),
                        id.provisional(), extras);
                outputNodes.add(new FactNode(id, NodeKind.FIELD, name, Optional.of(owner.orElseThrow().id()), attrs,
                        location));
                resolved.flatMap(SourceTypeResolver.ResolvedType::typeId).ifPresent(type -> fieldTypes
                        .computeIfAbsent(owner.orElseThrow().id(), ignored -> new HashMap<>()).put(name, type));
            }
        }
    }

    private static Map<SourceQueries.NodeKey, MethodDecl> emitMethods(
            ParsedUnit unit, SourceQueries queries, SourceTypeResolver resolver,
            Map<SourceQueries.NodeKey, List<String>> annotations, Map<TypeId, Map<String, TypeId>> fieldTypes,
            List<FactNode> outputNodes) {
        Map<SourceQueries.NodeKey, MethodDecl> methods = new LinkedHashMap<>();
        List<TSNode> declarations = new ArrayList<>();
        declarations.addAll(queries.nodes("declarations", "method.declaration", unit.root(), unit.source()));
        declarations.addAll(queries.nodes("declarations", "constructor.declaration", unit.root(), unit.source()));
        declarations.sort(java.util.Comparator.comparingInt(TSNode::getStartByte));
        for (TSNode declaration : declarations) {
            Optional<TypeDecl> owner = nearest(declaration.getParent(), unit.types());
            if (owner.isEmpty()) {
                continue;
            }
            boolean constructor = "constructor_declaration".equals(declaration.getType());
            Optional<TSNode> sourceNameNode = child(declaration, "name");
            if (sourceNameNode.isEmpty()) {
                continue;
            }
            String name = constructor ? "<init>" : unit.text(sourceNameNode.orElseThrow());
            List<Parameter> parameters = parameters(unit, declaration, resolver);
            int arity = parameters.size();
            Optional<String> returnDescriptor = constructor ? Optional.of("V")
                    : child(declaration, "type").flatMap(node -> resolver.resolve(unit.text(node)))
                    .map(SourceTypeResolver.ResolvedType::descriptor);
            Optional<String> descriptor = methodDescriptor(parameters, returnDescriptor);
            SourceLocation location = unit.location(declaration);
            boolean provisionalOwner = hasAncestorBefore(declaration.getParent(), owner.orElseThrow().node(),
                    "object_creation_expression");
            SymbolId id = descriptor.isPresent() && !provisionalOwner
                    ? canonical(new MethodId(owner.orElseThrow().id().binaryName(), name, descriptor.orElseThrow()))
                    : new ProvisionalId(NodeKind.METHOD, owner.orElseThrow().id(), name, arity,
                    location.line(), location.endLine());
            Map<String, JsonValue> extras = new LinkedHashMap<>();
            extras.put("arity", new NumberValue(BigDecimal.valueOf(arity)));
            extras.put("ownerProvisional", new BooleanValue(provisionalOwner));
            extras.put("parameterTypes", new ArrayValue(parameters.stream()
                    .map(parameter -> (JsonValue) new StringValue(parameter.sourceType())).toList()));
            if (!constructor) {
                child(declaration, "type").ifPresent(node -> extras.put("returnType", new StringValue(unit.text(node))));
            }
            Map<String, JsonValue> attrs = attributes(
                    annotations.getOrDefault(SourceQueries.NodeKey.of(declaration), List.of()),
                    id.provisional(), extras);
            FactNode factNode = new FactNode(id, NodeKind.METHOD, name, Optional.of(owner.orElseThrow().id()), attrs,
                    location);
            outputNodes.add(factNode);
            Map<String, TypeId> ownerFields = fieldTypes.getOrDefault(owner.orElseThrow().id(), Map.of());
            Map<String, TypeId> variables = new HashMap<>(ownerFields);
            parameters.forEach(parameter -> parameter.typeId().ifPresent(type -> variables.put(parameter.name(), type)));
            methods.put(SourceQueries.NodeKey.of(declaration),
                    new MethodDecl(declaration, factNode, owner.orElseThrow(), variables, Set.copyOf(ownerFields.keySet())));
        }
        return methods;
    }

    private static void populateVariables(ParsedUnit unit, SourceQueries queries, SourceTypeResolver resolver,
                                          Map<SourceQueries.NodeKey, MethodDecl> methods) {
        for (TSNode local : queries.nodes("variables", "variable.local", unit.root(), unit.source())) {
            Optional<MethodDecl> method = nearest(local.getParent(), methods);
            Optional<TSNode> typeNode = child(local, "type");
            if (method.isEmpty() || typeNode.isEmpty()) {
                continue;
            }
            Optional<TypeId> type = resolver.resolve(unit.text(typeNode.orElseThrow()))
                    .flatMap(SourceTypeResolver.ResolvedType::typeId);
            if (type.isEmpty()) {
                continue;
            }
            for (TSNode declarator : descendantsOfType(local, "variable_declarator")) {
                child(declarator, "name").ifPresent(name -> method.orElseThrow().variables()
                        .put(unit.text(name), type.orElseThrow()));
            }
        }
        for (TSNode parameter : queries.nodes("variables", "variable.parameter", unit.root(), unit.source())) {
            Optional<MethodDecl> method = nearest(parameter.getParent(), methods);
            Optional<TSNode> typeNode = child(parameter, "type");
            Optional<TSNode> nameNode = child(parameter, "name");
            if (method.isEmpty() || typeNode.isEmpty() || nameNode.isEmpty()) {
                continue;
            }
            String sourceType = unit.text(typeNode.orElseThrow())
                    + ("spread_parameter".equals(parameter.getType()) ? "..." : "");
            resolver.resolve(sourceType).flatMap(SourceTypeResolver.ResolvedType::typeId)
                    .ifPresent(type -> method.orElseThrow().variables().put(unit.text(nameNode.orElseThrow()), type));
        }
    }

    private static void emitCalls(ParsedUnit unit, SourceQueries queries, SourceTypeResolver resolver,
                                  SourceTypeResolver.TypeIndex typeIndex,
                                  Map<SourceQueries.NodeKey, MethodDecl> methods, List<EdgeFact> outputEdges) {
        for (TSNode call : queries.nodes("invocations", "call", unit.root(), unit.source())) {
            Optional<MethodDecl> method = nearest(call.getParent(), methods);
            Optional<TSNode> nameNode = child(call, "name");
            Optional<TSNode> argumentsNode = child(call, "arguments");
            if (method.isEmpty() || nameNode.isEmpty() || argumentsNode.isEmpty()) {
                continue;
            }
            Optional<TSNode> object = child(call, "object");
            String receiver = object.map(unit::text).orElse("this");
            Optional<TypeId> receiverType = resolveReceiver(receiver, object, method.orElseThrow(), resolver);
            String methodName = unit.text(nameNode.orElseThrow());
            int arity = argumentCount(argumentsNode.orElseThrow());
            DynamicCallKind dynamicKind = dynamicKind(methodName, receiver, receiverType, method.orElseThrow());
            List<String> literals = dynamicKind == DynamicCallKind.NONE ? List.of()
                    : stringLiterals(unit, argumentsNode.orElseThrow());
            DispatchKind dispatch = dispatch(dynamicKind, receiver, receiverType, method.orElseThrow(), typeIndex);
            SourceLocation location = unit.location(call);
            int evidenceLine = line(nameNode.orElseThrow().getStartPoint());
            Evidence.Ast evidence = new Evidence.Ast(unit.displayPath(), evidenceLine, unit.text(call));
            SourceCallSite site = new SourceCallSite(method.orElseThrow().node().id(), location,
                    call.getStartPoint().getColumn() + 1);
            outputEdges.add(new SourceCallFact(method.orElseThrow().node().id(), site, evidence, receiver,
                    methodName, arity, receiverType, dynamicKind, dispatch, literals));
        }
    }

    private static Optional<TypeId> resolveReceiver(String receiver, Optional<TSNode> object, MethodDecl method,
                                                    SourceTypeResolver resolver) {
        if (object.isEmpty() || "this".equals(receiver) || "super".equals(receiver)) {
            return Optional.of(method.owner().id());
        }
        String name = receiver;
        if (receiver.startsWith("this.")) {
            name = receiver.substring("this.".length());
            return Optional.ofNullable(method.variables().get(name));
        }
        if (isIdentifier(name)) {
            TypeId variable = method.variables().get(name);
            if (variable != null) {
                return Optional.of(variable);
            }
            return Character.isUpperCase(name.charAt(0))
                    ? resolver.resolve(name).flatMap(SourceTypeResolver.ResolvedType::typeId)
                    : Optional.empty();
        }
        int lastDot = receiver.lastIndexOf('.');
        if (lastDot > 0 && lastDot < receiver.length() - 1
                && Character.isUpperCase(receiver.charAt(lastDot + 1))) {
            return resolver.resolve(receiver).flatMap(SourceTypeResolver.ResolvedType::typeId);
        }
        return Optional.empty();
    }

    private static DynamicCallKind dynamicKind(String methodName, String receiver,
                                               Optional<TypeId> receiverType, MethodDecl method) {
        String binary = receiverType.map(TypeId::binaryName).orElse("");
        if ("getBean".equals(methodName)
                && (binary.contains("ApplicationContext") || binary.contains("BeanFactory"))) {
            return DynamicCallKind.GET_BEAN;
        }
        if ("invoke".equals(methodName) && "java.lang.reflect.Method".equals(binary)) {
            return DynamicCallKind.REFLECTION;
        }
        if ("forName".equals(methodName) && "java.lang.Class".equals(binary)) {
            return DynamicCallKind.CLASS_LOADING;
        }
        if ((methodName.startsWith("find") || "invokeExact".equals(methodName))
                && binary.startsWith("java.lang.invoke.")) {
            return DynamicCallKind.METHOD_HANDLE;
        }
        String fieldName = receiver.startsWith("this.") ? receiver.substring("this.".length()) : receiver;
        if ("get".equals(methodName) && "java.util.Map".equals(binary) && method.fieldNames().contains(fieldName)) {
            return DynamicCallKind.REGISTRY;
        }
        return DynamicCallKind.NONE;
    }

    private static DispatchKind dispatch(DynamicCallKind dynamicKind, String receiver,
                                         Optional<TypeId> receiverType, MethodDecl method,
                                         SourceTypeResolver.TypeIndex typeIndex) {
        return switch (dynamicKind) {
            case GET_BEAN, CLASS_LOADING, METHOD_HANDLE -> DispatchKind.DYNAMIC;
            case REFLECTION -> DispatchKind.REFLECTION;
            case REGISTRY -> DispatchKind.REGISTRY;
            case NONE -> {
                if (receiverType.isPresent() && typeIndex.isInterface(receiverType.orElseThrow())) {
                    yield DispatchKind.INTERFACE;
                }
                String variableName = receiver.startsWith("this.")
                        ? receiver.substring("this.".length()) : receiver;
                boolean variable = method.variables().containsKey(variableName);
                boolean typeReceiver = receiverType.isPresent() && !variable && looksLikeTypeReference(receiver)
                        && !"this".equals(receiver) && !"super".equals(receiver);
                yield typeReceiver ? DispatchKind.STATIC : DispatchKind.VIRTUAL;
            }
        };
    }

    private static int argumentCount(TSNode arguments) {
        int count = 0;
        for (int index = 0; index < arguments.getNamedChildCount(); index++) {
            TSNode argument = arguments.getNamedChild(index);
            if (argument != null && !argument.isNull() && !argument.isExtra()) {
                count++;
            }
        }
        return count;
    }

    private static List<String> stringLiterals(ParsedUnit unit, TSNode arguments) {
        List<String> literals = new ArrayList<>();
        for (int index = 0; index < arguments.getNamedChildCount(); index++) {
            TSNode argument = arguments.getNamedChild(index);
            if (argument != null && !argument.isNull() && "string_literal".equals(argument.getType())) {
                literals.add(unquote(unit.text(argument)));
            }
        }
        return List.copyOf(literals);
    }

    private static String unquote(String literal) {
        if (literal.length() < 2 || literal.charAt(0) != '"' || literal.charAt(literal.length() - 1) != '"') {
            return literal;
        }
        String body = literal.substring(1, literal.length() - 1);
        StringBuilder value = new StringBuilder(body.length());
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character != '\\' || index + 1 >= body.length()) {
                value.append(character);
                continue;
            }
            char escaped = body.charAt(++index);
            switch (escaped) {
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case '\'', '"', '\\' -> value.append(escaped);
                default -> {
                    value.append('\\');
                    value.append(escaped);
                }
            }
        }
        return value.toString();
    }

    private static List<Parameter> parameters(ParsedUnit unit, TSNode method, SourceTypeResolver resolver) {
        Optional<TSNode> parametersNode = child(method, "parameters");
        if (parametersNode.isEmpty()) {
            return List.of();
        }
        List<Parameter> parameters = new ArrayList<>();
        TSNode list = parametersNode.orElseThrow();
        for (int index = 0; index < list.getNamedChildCount(); index++) {
            TSNode parameter = list.getNamedChild(index);
            if (parameter == null || parameter.isNull() || "receiver_parameter".equals(parameter.getType())) {
                continue;
            }
            Optional<TSNode> typeNode = child(parameter, "type");
            Optional<TSNode> nameNode = child(parameter, "name");
            if (typeNode.isEmpty() || nameNode.isEmpty()) {
                continue;
            }
            String sourceType = unit.text(typeNode.orElseThrow())
                    + ("spread_parameter".equals(parameter.getType()) ? "..." : "");
            Optional<SourceTypeResolver.ResolvedType> resolved = resolver.resolve(sourceType);
            parameters.add(new Parameter(unit.text(nameNode.orElseThrow()), sourceType,
                    resolved.map(SourceTypeResolver.ResolvedType::descriptor),
                    resolved.flatMap(SourceTypeResolver.ResolvedType::typeId)));
        }
        return List.copyOf(parameters);
    }

    private static Optional<String> methodDescriptor(List<Parameter> parameters, Optional<String> returnDescriptor) {
        if (returnDescriptor.isEmpty() || parameters.stream().anyMatch(parameter -> parameter.descriptor().isEmpty())) {
            return Optional.empty();
        }
        StringBuilder descriptor = new StringBuilder("(");
        parameters.forEach(parameter -> descriptor.append(parameter.descriptor().orElseThrow()));
        return Optional.of(descriptor.append(')').append(returnDescriptor.orElseThrow()).toString());
    }

    private static Map<SourceQueries.NodeKey, List<String>> annotations(ParsedUnit unit, SourceQueries queries) {
        Map<SourceQueries.NodeKey, Set<String>> collected = new HashMap<>();
        Set<String> declarationTypes = Set.of("class_declaration", "interface_declaration", "enum_declaration",
                "record_declaration", "annotation_type_declaration", "method_declaration",
                "constructor_declaration", "field_declaration");
        for (TSNode annotation : queries.nodes("annotations", "annotation", unit.root(), unit.source())) {
            Optional<TSNode> declaration = ancestor(annotation.getParent(), declarationTypes);
            Optional<TSNode> name = child(annotation, "name");
            if (declaration.isPresent() && name.isPresent()) {
                collected.computeIfAbsent(SourceQueries.NodeKey.of(declaration.orElseThrow()),
                                ignored -> new LinkedHashSet<>())
                        .add(unit.text(name.orElseThrow()));
            }
        }
        Map<SourceQueries.NodeKey, List<String>> result = new HashMap<>();
        collected.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return result;
    }

    private static Map<String, JsonValue> attributes(List<String> annotations, boolean provisional,
                                                     Map<String, JsonValue> extras) {
        Map<String, JsonValue> attrs = new LinkedHashMap<>();
        attrs.put("annotations", new ArrayValue(annotations.stream()
                .map(annotation -> (JsonValue) new StringValue(annotation)).toList()));
        attrs.put("generated", new BooleanValue(false));
        attrs.put("provisional", new BooleanValue(provisional));
        attrs.putAll(extras);
        return Map.copyOf(attrs);
    }

    private static boolean hasAncestorBefore(TSNode start, TSNode boundary, String type) {
        TSNode current = start;
        SourceQueries.NodeKey boundaryKey = SourceQueries.NodeKey.of(boundary);
        while (current != null && !current.isNull()
                && !SourceQueries.NodeKey.of(current).equals(boundaryKey)) {
            if (type.equals(current.getType())) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static Optional<TSNode> ancestor(TSNode start, Set<String> types) {
        TSNode current = start;
        while (current != null && !current.isNull()) {
            if (types.contains(current.getType())) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static <T> Optional<T> nearest(TSNode start, Map<SourceQueries.NodeKey, T> declarations) {
        TSNode current = start;
        while (current != null && !current.isNull()) {
            T value = declarations.get(SourceQueries.NodeKey.of(current));
            if (value != null) {
                return Optional.of(value);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static List<TSNode> descendantsOfType(TSNode root, String type) {
        List<TSNode> found = new ArrayList<>();
        collectDescendants(root, type, found);
        return found;
    }

    private static void collectDescendants(TSNode node, String type, List<TSNode> found) {
        for (int index = 0; index < node.getNamedChildCount(); index++) {
            TSNode child = node.getNamedChild(index);
            if (child == null || child.isNull()) {
                continue;
            }
            if (type.equals(child.getType())) {
                found.add(child);
            } else {
                collectDescendants(child, type, found);
            }
        }
    }

    private static Optional<TSNode> firstError(TSNode node) {
        if (node.isError() || node.isMissing()) {
            return Optional.of(node);
        }
        for (int index = 0; index < node.getNamedChildCount(); index++) {
            TSNode child = node.getNamedChild(index);
            if (child != null && !child.isNull() && child.hasError()) {
                Optional<TSNode> error = firstError(child);
                if (error.isPresent()) {
                    return error;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<TSNode> child(TSNode node, String field) {
        TSNode child = node.getChildByFieldName(field);
        return child == null || child.isNull() ? Optional.empty() : Optional.of(child);
    }

    private static SymbolId canonical(dev.ghostmap.model.CanonicalId id) {
        return new SymbolId.Canonical(id);
    }

    private static boolean looksLikeTypeReference(String value) {
        int lastDot = value.lastIndexOf('.');
        int typeStart = lastDot < 0 ? 0 : lastDot + 1;
        return typeStart < value.length() && Character.isUpperCase(value.charAt(typeStart));
    }

    private static boolean isIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static int line(TSPoint point) {
        return point.getRow() + 1;
    }

    private static String portable(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static Diagnostic unitDiagnostic(ParsedUnit unit, String action, RuntimeException exception) {
        String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return new Diagnostic(DiagnosticCode.PARSE_ERROR, DiagnosticSeverity.ERROR, unit.displayPath(), 0,
                action + ": " + detail);
    }

    private static Diagnostic rootDiagnostic(DiagnosticCode code, Path root, String message) {
        return new Diagnostic(code, DiagnosticSeverity.ERROR, portable(root), 0, message);
    }

    private static LayerFacts facts(List<FactNode> nodes, List<EdgeFact> edges, List<Diagnostic> diagnostics) {
        return new LayerFacts(LayerId.AST, nodes, edges, diagnostics);
    }

    private record TypeDecl(TSNode node, TypeId id, String name, boolean interfaceType, Optional<TypeId> outer) {
    }

    private record Parameter(String name, String sourceType, Optional<String> descriptor, Optional<TypeId> typeId) {
    }

    private record MethodDecl(TSNode declaration, FactNode node, TypeDecl owner,
                              Map<String, TypeId> variables, Set<String> fieldNames) {
    }

    private static final class ParsedUnit implements AutoCloseable {
        private final String displayPath;
        private final String source;
        private final byte[] sourceBytes;
        private final TSTree tree;
        private final TSNode root;
        private final Map<SourceQueries.NodeKey, TypeDecl> types = new LinkedHashMap<>();
        private String packageName = "";
        private Map<String, String> imports = Map.of();

        private ParsedUnit(String displayPath, String source, TSTree tree, TSNode root) {
            this.displayPath = displayPath;
            this.source = source;
            sourceBytes = source.getBytes(StandardCharsets.UTF_8);
            this.tree = tree;
            this.root = root;
        }

        void readCompilationUnit(SourceQueries queries) {
            List<TSNode> packages = queries.nodes("compilation-unit", "package.declaration", root, source);
            if (!packages.isEmpty()) {
                String declaration = text(packages.get(0)).strip();
                packageName = declaration.substring("package".length(), declaration.length() - 1).strip();
            }
            Map<String, String> foundImports = new HashMap<>();
            for (TSNode importNode : queries.nodes("compilation-unit", "import.declaration", root, source)) {
                String declaration = text(importNode).strip();
                String imported = declaration.substring("import".length(), declaration.length() - 1).strip();
                if (imported.startsWith("static ") || imported.endsWith(".*")) {
                    continue;
                }
                int dot = imported.lastIndexOf('.');
                if (dot > 0 && dot < imported.length() - 1) {
                    foundImports.put(imported.substring(dot + 1), imported);
                }
            }
            imports = Map.copyOf(foundImports);
        }

        String text(TSNode node) {
            int start = node.getStartByte();
            int end = node.getEndByte();
            return new String(sourceBytes, start, end - start, StandardCharsets.UTF_8);
        }

        SourceLocation location(TSNode node) {
            int startLine = line(node.getStartPoint());
            TSPoint end = node.getEndPoint();
            int endLine = end.getRow() + (end.getColumn() == 0 ? 0 : 1);
            return new SourceLocation(displayPath, startLine, Math.max(startLine, endLine));
        }

        String displayPath() {
            return displayPath;
        }

        String source() {
            return source;
        }

        TSNode root() {
            return root;
        }

        Map<SourceQueries.NodeKey, TypeDecl> types() {
            return types;
        }

        String packageName() {
            return packageName;
        }

        Map<String, String> imports() {
            return imports;
        }

        @Override
        public void close() {
            tree.close();
        }
    }
}
