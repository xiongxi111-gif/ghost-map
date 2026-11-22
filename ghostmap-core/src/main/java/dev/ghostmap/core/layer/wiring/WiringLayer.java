package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.DiagnosticSeverity;
import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.Layer;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.core.layer.wiring.XmlBeanDocumentParser.AliasDeclaration;
import dev.ghostmap.core.layer.wiring.XmlBeanDocumentParser.ImportDeclaration;
import dev.ghostmap.core.layer.wiring.XmlBeanDocumentParser.ParsedDocument;
import dev.ghostmap.core.layer.wiring.XmlBeanDocumentParser.RawBean;
import dev.ghostmap.core.layer.wiring.XmlBeanDocumentParser.RawInjection;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Secure, read-only Spring XML wiring analysis using only JDK StAX. */
public final class WiringLayer implements Layer {
    @Override
    public LayerId id() {
        return LayerId.WIRING;
    }

    @Override
    public LayerFacts analyse(LayerInput input) {
        if (input == null) {
            throw new NullPointerException("input");
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<Path> roots = validatedRoots(input.resourceRoots(), diagnostics);
        List<Path> xmlFiles = discoverXml(roots, diagnostics);
        Map<Path, ParsedDocument> documents = new LinkedHashMap<>();
        XmlBeanDocumentParser parser = new XmlBeanDocumentParser(diagnostics::add);
        for (Path xml : xmlFiles) {
            parseWithImports(xml, context(xml, roots), roots, parser, documents,
                    new LinkedHashSet<>(), diagnostics, null);
        }

        List<RawBean> rawBeans = documents.values().stream()
                .sorted(Comparator.comparing(ParsedDocument::context))
                .flatMap(document -> document.beans().stream())
                .sorted(Comparator.comparing((RawBean bean) -> bean.beanId().value()).thenComparingInt(RawBean::line))
                .toList();
        BeanRegistry registry = new BeanRegistry(rawBeans, documents.values(), diagnostics);
        List<RawBean> uniqueBeans = registry.uniqueBeans();
        Map<BeanId, EffectiveBean> effective = new LinkedHashMap<>();
        for (RawBean bean : uniqueBeans) {
            resolveEffective(bean, registry, effective, new LinkedHashSet<>(), diagnostics);
        }

        AnnotationWiringScanner.ScanResult annotationScan = new AnnotationWiringScanner(diagnostics)
                .scan(input.classRoots());
        validateImportResources(annotationScan.importResources(), roots, documents.values(), diagnostics);
        Map<BeanId, Set<String>> aliases = registry.resolvedAliases();
        List<WiringBeanRegistration> registrations = new ArrayList<>();
        for (RawBean bean : uniqueBeans) {
            EffectiveBean resolved = effective.get(bean.beanId());
            xmlRegistration(bean, resolved, aliases.getOrDefault(bean.beanId(), Set.of()))
                    .ifPresent(registrations::add);
        }
        registrations.addAll(annotationScan.registrations());
        UnifiedBeanRegistry unified = new UnifiedBeanRegistry(registrations, annotationScan.hierarchy(), diagnostics);

        List<FactNode> nodes = new ArrayList<>();
        List<EdgeFact> edges = new ArrayList<>();
        for (RawBean bean : uniqueBeans) {
            EffectiveBean resolved = effective.get(bean.beanId());
            nodes.add(beanNode(bean, resolved, aliases.getOrDefault(bean.beanId(), Set.of())));
            if (!bean.abstractBean()) {
                emitBind(bean, resolved, edges, diagnostics);
                emitInjections(bean, resolved, unified, edges, diagnostics);
            }
        }
        nodes.addAll(annotationScan.nodes());
        annotationScan.binds().stream().filter(bind ->
                bind.from() instanceof SymbolId.Canonical canonical
                        && canonical.id() instanceof BeanId beanId
                        && unified.byId(beanId).isPresent())
                .forEach(edges::add);
        emitAnnotationInjections(annotationScan.injectionSites(), unified, edges, diagnostics);
        nodes = distinctNodes(nodes, diagnostics);
        nodes.sort(Comparator.comparing(node -> node.id().value()));
        edges.sort(Comparator.comparing(edge -> edge.from().value() + '|' + edge.toString()));
        diagnostics.sort(Comparator.comparing((Diagnostic diagnostic) -> diagnostic.code().name())
                .thenComparing(Diagnostic::file).thenComparingInt(Diagnostic::line)
                .thenComparing(Diagnostic::message));
        return facts(nodes, edges, diagnostics);
    }

    private static List<Path> validatedRoots(List<Path> inputs, List<Diagnostic> diagnostics) {
        List<Path> roots = new ArrayList<>();
        for (Path input : inputs) {
            if (!Files.exists(input)) {
                diagnostics.add(diagnostic(DiagnosticCode.RESOURCE_ROOT_NOT_FOUND, input.toString(), 0,
                        "resource root does not exist"));
            } else if (!Files.isDirectory(input)) {
                diagnostics.add(diagnostic(DiagnosticCode.RESOURCE_ROOT_UNSUPPORTED, input.toString(), 0,
                        "resource root must be a directory"));
            } else if (!Files.isReadable(input)) {
                diagnostics.add(diagnostic(DiagnosticCode.RESOURCE_ROOT_READ_ERROR, input.toString(), 0,
                        "resource root is not readable"));
            } else {
                try {
                    roots.add(input.toRealPath());
                } catch (IOException exception) {
                    diagnostics.add(diagnostic(DiagnosticCode.RESOURCE_ROOT_READ_ERROR, input.toString(), 0,
                            "cannot resolve resource root: " + detail(exception)));
                }
            }
        }
        return roots.stream().distinct().sorted().toList();
    }

    private static List<Path> discoverXml(List<Path> roots, List<Diagnostic> diagnostics) {
        Set<Path> files = new LinkedHashSet<>();
        for (Path root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                                .endsWith(".xml"))
                        .map(path -> path.toAbsolutePath().normalize())
                        .forEach(files::add);
            } catch (IOException | UncheckedIOException | SecurityException exception) {
                diagnostics.add(diagnostic(DiagnosticCode.RESOURCE_ROOT_READ_ERROR, root.toString(), 0,
                        "cannot enumerate resource root: " + detail(exception)));
            }
        }
        return files.stream().sorted().toList();
    }

    private static void parseWithImports(Path file, String display, List<Path> roots,
                                         XmlBeanDocumentParser parser, Map<Path, ParsedDocument> documents,
                                         Set<Path> visiting, List<Diagnostic> diagnostics,
                                         ImportDeclaration importedBy) {
        Path real;
        try {
            real = file.toRealPath();
        } catch (IOException exception) {
            if (importedBy != null) {
                diagnostics.add(diagnostic(DiagnosticCode.XML_IMPORT_MISSING, importedBy.file(), importedBy.line(),
                        "import resource does not exist: " + importedBy.resource()));
            }
            return;
        }
        if (!insideRoots(real, roots)) {
            String source = importedBy == null ? display : importedBy.file();
            int line = importedBy == null ? 0 : importedBy.line();
            diagnostics.add(diagnostic(DiagnosticCode.XML_IMPORT_OUTSIDE_ROOT, source, line,
                    "XML resource is outside analysed roots"));
            return;
        }
        if (visiting.contains(real)) {
            String source = importedBy == null ? display : importedBy.file();
            int line = importedBy == null ? 0 : importedBy.line();
            diagnostics.add(diagnostic(DiagnosticCode.XML_IMPORT_CYCLE, source, line,
                    "XML import cycle reaches " + context(real, roots)));
            return;
        }
        if (documents.containsKey(real)) {
            return;
        }
        visiting.add(real);
        Optional<ParsedDocument> parsed = parser.parse(real, display);
        if (parsed.isPresent()) {
            ParsedDocument document = parsed.orElseThrow();
            documents.put(real, document);
            for (ImportDeclaration declaration : document.imports()) {
                Optional<Path> imported = resolveImport(real, declaration.resource(), roots);
                if (imported.isEmpty()) {
                    DiagnosticCode code = forbiddenImport(real, declaration.resource(), roots)
                            ? DiagnosticCode.XML_IMPORT_OUTSIDE_ROOT : DiagnosticCode.XML_IMPORT_MISSING;
                    diagnostics.add(diagnostic(code, declaration.file(), declaration.line(),
                            code == DiagnosticCode.XML_IMPORT_MISSING
                                    ? "import resource does not exist: " + declaration.resource()
                                    : "remote, absolute, or out-of-root import is forbidden"));
                    continue;
                }
                Path target = imported.orElseThrow();
                parseWithImports(target, context(target, roots), roots, parser, documents, visiting,
                        diagnostics, declaration);
            }
        }
        visiting.remove(real);
    }

    private static Optional<Path> resolveImport(Path importingFile, String resource, List<Path> roots) {
        if (resource == null || resource.isBlank() || remoteOrOutside(resource)) {
            return Optional.empty();
        }
        String local = resource.startsWith("classpath:") ? resource.substring("classpath:".length()) : resource;
        while (local.startsWith("/")) {
            local = local.substring(1);
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(importingFile.getParent().resolve(local).normalize());
        for (Path root : roots) {
            candidates.add(root.resolve(local).normalize());
        }
        for (Path candidate : candidates) {
            if (!insideRoots(candidate, roots) || !Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                Path real = candidate.toRealPath();
                if (insideRoots(real, roots)) {
                    return Optional.of(real);
                }
            } catch (IOException ignored) {
                // The caller emits one missing-import diagnostic.
            }
        }
        return Optional.empty();
    }

    private static boolean forbiddenImport(Path importingFile, String resource, List<Path> roots) {
        if (remoteOrOutside(resource)) {
            return true;
        }
        String local = resource.startsWith("classpath:") ? resource.substring("classpath:".length()) : resource;
        while (local.startsWith("/")) {
            local = local.substring(1);
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(importingFile.getParent().resolve(local).normalize());
        for (Path root : roots) {
            candidates.add(root.resolve(local).normalize());
        }
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                try {
                    if (!insideRoots(candidate.toRealPath(), roots)) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // An unreadable candidate is handled as missing rather than followed.
                }
            }
        }
        return candidates.stream().noneMatch(candidate -> insideRoots(candidate, roots));
    }

    private static boolean remoteOrOutside(String resource) {
        if (resource == null || resource.isBlank()) {
            return false;
        }
        if (resource.startsWith("classpath:")) {
            return false;
        }
        try {
            URI uri = URI.create(resource);
            return uri.isAbsolute() || Path.of(resource).isAbsolute();
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private static boolean insideRoots(Path path, List<Path> roots) {
        Path normalized = path.toAbsolutePath().normalize();
        return roots.stream().anyMatch(normalized::startsWith);
    }

    private static String context(Path file, List<Path> roots) {
        Path absolute = file.toAbsolutePath().normalize();
        return roots.stream().filter(absolute::startsWith)
                .map(root -> portable(root.relativize(absolute)))
                .min(Comparator.comparingInt(String::length).thenComparing(String::compareTo))
                .orElseGet(() -> file.getFileName().toString());
    }

    private static EffectiveBean resolveEffective(RawBean bean, BeanRegistry registry,
                                                  Map<BeanId, EffectiveBean> cache,
                                                  Set<BeanId> visiting, List<Diagnostic> diagnostics) {
        EffectiveBean cached = cache.get(bean.beanId());
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(bean.beanId())) {
            diagnostics.add(diagnostic(DiagnosticCode.XML_PARENT_CYCLE, bean.file(), bean.line(),
                    "parent cycle includes " + bean.beanId().value()));
            return EffectiveBean.own(bean);
        }
        EffectiveBean result = EffectiveBean.own(bean);
        if (!bean.parentName().isBlank()) {
            Optional<RawBean> parent = registry.resolve(bean.parentName(), new LinkedHashSet<>());
            if (parent.isEmpty()) {
                diagnostics.add(diagnostic(DiagnosticCode.XML_PARENT_MISSING, bean.file(), bean.line(),
                        "parent bean not found or ambiguous: " + bean.parentName()));
            } else if (visiting.contains(parent.orElseThrow().beanId())) {
                diagnostics.add(diagnostic(DiagnosticCode.XML_PARENT_CYCLE, bean.file(), bean.line(),
                        "parent cycle reaches " + parent.orElseThrow().beanId().value()));
            } else {
                EffectiveBean inherited = resolveEffective(parent.orElseThrow(), registry, cache,
                        visiting, diagnostics);
                result = EffectiveBean.inherit(bean, parent.orElseThrow(), inherited);
            }
        }
        visiting.remove(bean.beanId());
        cache.put(bean.beanId(), result);
        return result;
    }

    private static void validateImportResources(
            List<AnnotationWiringScanner.ImportResourceRequest> requests,
            List<Path> roots, java.util.Collection<ParsedDocument> documents,
            List<Diagnostic> diagnostics) {
        Set<Path> parsed = documents.stream().map(ParsedDocument::path)
                .map(path -> path.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
        for (AnnotationWiringScanner.ImportResourceRequest request : requests) {
            String resource = request.resource();
            if (remoteOrOutside(resource)) {
                diagnostics.add(diagnostic(DiagnosticCode.XML_IMPORT_OUTSIDE_ROOT, request.classFile(),
                        request.line(), "remote or absolute @ImportResource is forbidden"));
                continue;
            }
            String local = resource.startsWith("classpath:")
                    ? resource.substring("classpath:".length()) : resource;
            while (local.startsWith("/")) {
                local = local.substring(1);
            }
            boolean present = false;
            for (Path root : roots) {
                Path candidate = root.resolve(local).normalize();
                if (insideRoots(candidate, roots) && parsed.contains(candidate.toAbsolutePath().normalize())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                diagnostics.add(diagnostic(DiagnosticCode.XML_IMPORT_MISSING, request.classFile(), request.line(),
                        "local @ImportResource was not found in analysed resource roots: " + resource));
            }
        }
    }

    private static Optional<WiringBeanRegistration> xmlRegistration(RawBean bean, EffectiveBean effective,
                                                                     Set<String> aliases) {
        if (bean.abstractBean() || effective.className().isBlank()
                || effective.className().contains("${") || effective.className().contains("#{")) {
            return Optional.empty();
        }
        try {
            TypeId type = new TypeId(effective.className());
            Set<String> names = new LinkedHashSet<>(aliases);
            names.add(bean.beanId().beanId());
            return Optional.of(new WiringBeanRegistration(bean.beanId(), type, names, names, false));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static List<FactNode> distinctNodes(List<FactNode> candidates, List<Diagnostic> diagnostics) {
        Map<String, FactNode> nodes = new LinkedHashMap<>();
        for (FactNode node : candidates.stream().sorted(Comparator.comparing(fact -> fact.id().value())).toList()) {
            FactNode previous = nodes.putIfAbsent(node.id().value(), node);
            if (previous != null) {
                String file = node.sourceLocation().map(SourceLocation::file)
                        .orElseGet(() -> node.attrs().get("classFile") instanceof StringValue value
                                ? value.value() : "annotations");
                diagnostics.add(diagnostic(DiagnosticCode.ANNOTATION_DUPLICATE_BEAN, file, 1,
                        "duplicate annotation bean id: " + node.id().value()));
            }
        }
        return new ArrayList<>(nodes.values());
    }

    private static void emitAnnotationInjections(List<AnnotationInjectionSite> sites,
                                                  UnifiedBeanRegistry registry, List<EdgeFact> edges,
                                                  List<Diagnostic> diagnostics) {
        for (AnnotationInjectionSite site : sites) {
            WiringResolution resolution = registry.resolve(site);
            if (resolution.target().isPresent()) {
                BeanId target = resolution.target().orElseThrow();
                Evidence.Wiring evidence = new Evidence.Wiring(site.classFile(), site.line(), target);
                edges.add(new WiringInjectionFact(canonical(site.ownerBean()), canonical(target), site.point(),
                        evidence, List.of()));
            } else {
                edges.add(new UnresolvedWiringFact(canonical(site.ownerBean()), site.point(),
                        site.requiredType().value(), site.resourceName().or(() -> site.qualifier()),
                        resolution.candidates(), site.classFile(), site.line()));
                diagnostics.add(diagnostic(DiagnosticCode.WIRING_UNRESOLVED_INJECTION,
                        site.classFile(), site.line(), "unresolved injection of " + site.requiredType().value()
                                + " into " + site.ownerBean().value() + "; candidates="
                                + resolution.candidates().stream().map(BeanId::value).toList()));
            }
        }
    }

    private static FactNode beanNode(RawBean bean, EffectiveBean effective, Set<String> aliases) {
        Map<String, JsonValue> attrs = new LinkedHashMap<>();
        attrs.put("abstract", new BooleanValue(bean.abstractBean()));
        attrs.put("aliases", array(aliases));
        attrs.put("generated", new BooleanValue(bean.inner()));
        attrs.put("inheritanceChain", beanIds(effective.inheritanceChain()));
        attrs.put("inner", new BooleanValue(bean.inner()));
        attrs.put("properties", array(effective.references().values().stream()
                .map(WiringLayer::valueSummary).collect(java.util.stream.Collectors.toSet())));
        attrs.put("references", array(effective.references().values().stream()
                .filter(WiringLayer::hasReference)
                .map(WiringLayer::referenceSummary).collect(java.util.stream.Collectors.toSet())));
        attrs.put("scope", new StringValue(effective.scope()));
        if (!effective.className().isBlank()) {
            attrs.put("class", new StringValue(effective.className()));
        }
        if (!bean.declaredClass().isBlank()) {
            attrs.put("declaredClass", new StringValue(bean.declaredClass()));
        }
        if (!bean.parentName().isBlank()) {
            attrs.put("parent", new StringValue(bean.parentName()));
        }
        return new FactNode(canonical(bean.beanId()), NodeKind.BEAN, bean.beanId().beanId(), Optional.empty(), attrs,
                new SourceLocation(bean.file(), bean.line(), bean.line()));
    }

    private static void emitBind(RawBean bean, EffectiveBean effective, List<EdgeFact> edges,
                                 List<Diagnostic> diagnostics) {
        if (effective.className().isBlank()) {
            return;
        }
        if (effective.className().contains("${") || effective.className().contains("#{")) {
            diagnostics.add(diagnostic(DiagnosticCode.XML_INVALID_CLASS, bean.file(), bean.line(),
                    "dynamic bean class cannot be bound statically: " + effective.className()));
            return;
        }
        try {
            TypeId type = new TypeId(effective.className());
            Evidence.Wiring evidence = new Evidence.Wiring(bean.file(), bean.line(), bean.beanId());
            edges.add(new WiringBindFact(canonical(bean.beanId()), canonical(type), evidence,
                    effective.classInheritanceChain()));
        } catch (IllegalArgumentException exception) {
            diagnostics.add(diagnostic(DiagnosticCode.XML_INVALID_CLASS, bean.file(), bean.line(),
                    "bean class is not a concrete binary name: " + effective.className()));
        }
    }

    private static void emitInjections(RawBean bean, EffectiveBean effective,
                                       UnifiedBeanRegistry unified, List<EdgeFact> edges,
                                       List<Diagnostic> diagnostics) {
        for (EffectiveInjection injection : effective.references().values()) {
            RawInjection raw = injection.raw();
            if (!hasReference(injection)) {
                continue;
            }
            List<BeanId> candidates = raw.directTarget()
                    .map(target -> unified.byId(target).stream().toList())
                    .orElseGet(() -> raw.referenceName().map(unified::byName).orElse(List.of()));
            if (candidates.size() != 1) {
                String reference = raw.directTarget().map(BeanId::value).orElseGet(() ->
                        raw.referenceName().orElse("<missing>"));
                diagnostics.add(diagnostic(DiagnosticCode.XML_REFERENCE_MISSING, raw.file(), raw.line(),
                        "bean reference not found or ambiguous: " + reference));
                edges.add(new UnresolvedWiringFact(canonical(bean.beanId()), raw.point(), "bean-name",
                        Optional.of(reference), candidates, raw.file(), raw.line()));
                continue;
            }
            BeanId targetId = candidates.get(0);
            Evidence.Wiring evidence = new Evidence.Wiring(raw.file(), raw.line(), targetId);
            edges.add(new WiringInjectionFact(canonical(bean.beanId()), canonical(targetId), raw.point(), evidence,
                    injection.inheritanceChain()));
        }
    }

    private static String valueSummary(EffectiveInjection injection) {
        RawInjection raw = injection.raw();
        String name = raw.point().name().orElseGet(() ->
                raw.point().index().isPresent() ? Integer.toString(raw.point().index().orElseThrow()) : "");
        String target = raw.directTarget().map(BeanId::value)
                .orElseGet(() -> raw.referenceName().orElseGet(() -> raw.literalValue().orElse("")));
        String operator = hasReference(injection) ? "->" : "=";
        return raw.point().kind() + ":" + name + operator + target;
    }

    private static boolean hasReference(EffectiveInjection injection) {
        return injection.raw().directTarget().isPresent() || injection.raw().referenceName().isPresent();
    }

    private static String referenceSummary(EffectiveInjection injection) {
        RawInjection raw = injection.raw();
        String target = raw.directTarget().map(BeanId::value).orElseGet(() -> raw.referenceName().orElse(""));
        String name = raw.point().name().orElseGet(() ->
                raw.point().index().isPresent() ? Integer.toString(raw.point().index().orElseThrow()) : "");
        return raw.point().kind() + ":" + name + "->" + target;
    }

    private static ArrayValue array(Set<String> values) {
        return new ArrayValue(values.stream().sorted().map(value -> (JsonValue) new StringValue(value)).toList());
    }

    private static ArrayValue beanIds(List<BeanId> values) {
        return new ArrayValue(values.stream().map(BeanId::value).map(value -> (JsonValue) new StringValue(value))
                .toList());
    }

    private static SymbolId canonical(CanonicalId id) {
        return new SymbolId.Canonical(id);
    }

    private static LayerFacts facts(List<FactNode> nodes, List<EdgeFact> edges, List<Diagnostic> diagnostics) {
        return new LayerFacts(LayerId.WIRING, nodes, edges, diagnostics);
    }

    private static Diagnostic diagnostic(DiagnosticCode code, String file, int line, String message) {
        return new Diagnostic(code, DiagnosticSeverity.ERROR, file.replace('\\', '/'), line, message);
    }

    private static String detail(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String portable(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private record EffectiveInjection(RawInjection raw, List<BeanId> inheritanceChain) {
        private EffectiveInjection {
            inheritanceChain = List.copyOf(inheritanceChain);
        }

        EffectiveInjection inheritedThrough(BeanId parent) {
            List<BeanId> chain = new ArrayList<>(inheritanceChain);
            chain.add(parent);
            return new EffectiveInjection(raw, chain);
        }
    }

    private record EffectiveBean(String className, String scope,
                                 Map<String, EffectiveInjection> references,
                                 List<BeanId> inheritanceChain,
                                 List<BeanId> classInheritanceChain) {
        private EffectiveBean {
            references = Map.copyOf(references);
            inheritanceChain = List.copyOf(inheritanceChain);
            classInheritanceChain = List.copyOf(classInheritanceChain);
        }

        static EffectiveBean own(RawBean bean) {
            Map<String, EffectiveInjection> references = new LinkedHashMap<>();
            bean.values().forEach((key, value) -> references.put(key, new EffectiveInjection(value, List.of())));
            return new EffectiveBean(bean.declaredClass(), defaultScope(bean.declaredScope()), references,
                    List.of(), List.of());
        }

        static EffectiveBean inherit(RawBean child, RawBean parent, EffectiveBean inherited) {
            Map<String, EffectiveInjection> references = new LinkedHashMap<>();
            inherited.references().forEach((key, value) ->
                    references.put(key, value.inheritedThrough(parent.beanId())));
            child.values().forEach((key, value) -> references.put(key, new EffectiveInjection(value, List.of())));
            boolean inheritsClass = child.declaredClass().isBlank();
            String className = inheritsClass ? inherited.className() : child.declaredClass();
            List<BeanId> classChain = inheritsClass ? append(inherited.classInheritanceChain(), parent.beanId())
                    : List.of();
            String scope = child.declaredScope().isBlank() ? inherited.scope() : child.declaredScope();
            return new EffectiveBean(className, scope, references,
                    append(inherited.inheritanceChain(), parent.beanId()), classChain);
        }

        private static String defaultScope(String scope) {
            return scope.isBlank() ? "singleton" : scope;
        }

        private static List<BeanId> append(List<BeanId> values, BeanId value) {
            List<BeanId> result = new ArrayList<>(values);
            result.add(value);
            return List.copyOf(result);
        }
    }

    private record WiringResolution(Optional<BeanId> target, List<BeanId> candidates) {
        private WiringResolution {
            candidates = List.copyOf(candidates);
        }
    }

    private static final class UnifiedBeanRegistry {
        private final Map<BeanId, WiringBeanRegistration> byId = new LinkedHashMap<>();
        private final Map<String, List<WiringBeanRegistration>> byName = new LinkedHashMap<>();
        private final Set<BeanId> conflictingIds = new HashSet<>();
        private final Map<String, Set<String>> hierarchy;

        private UnifiedBeanRegistry(List<WiringBeanRegistration> registrations,
                                    Map<String, Set<String>> hierarchy,
                                    List<Diagnostic> diagnostics) {
            this.hierarchy = Map.copyOf(hierarchy);
            for (WiringBeanRegistration registration : registrations.stream()
                    .sorted(Comparator.comparing(candidate -> candidate.beanId().value())).toList()) {
                if (conflictingIds.contains(registration.beanId())) {
                    continue;
                }
                WiringBeanRegistration previous = byId.putIfAbsent(registration.beanId(), registration);
                if (previous != null && !previous.equals(registration)) {
                    conflictingIds.add(registration.beanId());
                    byId.remove(registration.beanId());
                    byName.values().forEach(values -> values.removeIf(candidate ->
                            candidate.beanId().equals(registration.beanId())));
                    diagnostics.add(diagnostic(DiagnosticCode.ANNOTATION_DUPLICATE_BEAN,
                            "annotations", 1, "duplicate bean id: " + registration.beanId().value()));
                    continue;
                }
                if (previous == null) {
                    registration.names().forEach(name -> byName.computeIfAbsent(name,
                            ignored -> new ArrayList<>()).add(registration));
                }
            }
            byName.forEach((name, candidates) -> {
                long distinct = candidates.stream().map(WiringBeanRegistration::beanId).distinct().count();
                if (distinct > 1) {
                    diagnostics.add(diagnostic(DiagnosticCode.ANNOTATION_DUPLICATE_BEAN,
                            "annotations", 1, "conflicting bean name across XML and annotations: " + name));
                }
            });
        }

        Optional<BeanId> byId(BeanId id) {
            return Optional.ofNullable(byId.get(id)).map(WiringBeanRegistration::beanId);
        }

        List<BeanId> byName(String name) {
            return registrationsByName(name).stream().map(WiringBeanRegistration::beanId).distinct()
                    .sorted(Comparator.comparing(BeanId::value)).toList();
        }

        WiringResolution resolve(AnnotationInjectionSite site) {
            if (site.resourceName().isPresent()) {
                List<WiringBeanRegistration> named = registrationsByName(site.resourceName().orElseThrow());
                List<WiringBeanRegistration> compatible = named.stream()
                        .filter(candidate -> isAssignable(candidate.type(), site.requiredType())).toList();
                if (compatible.size() == 1) {
                    BeanId target = compatible.get(0).beanId();
                    return new WiringResolution(Optional.of(target), List.of(target));
                }
                if (!named.isEmpty() || site.explicitResourceName()) {
                    return unresolved(compatible.isEmpty() ? named : compatible);
                }
            }

            List<WiringBeanRegistration> candidates = byId.values().stream()
                    .filter(candidate -> isAssignable(candidate.type(), site.requiredType())).toList();
            if (site.qualifier().isPresent()) {
                String qualifier = site.qualifier().orElseThrow();
                candidates = candidates.stream().filter(candidate -> candidate.names().contains(qualifier)
                        || candidate.qualifiers().contains(qualifier)).toList();
            }
            if (candidates.size() == 1) {
                BeanId target = candidates.get(0).beanId();
                return new WiringResolution(Optional.of(target), List.of(target));
            }
            if (candidates.size() > 1) {
                List<WiringBeanRegistration> primary = candidates.stream()
                        .filter(WiringBeanRegistration::primary).toList();
                if (primary.size() == 1) {
                    BeanId target = primary.get(0).beanId();
                    return new WiringResolution(Optional.of(target), List.of(target));
                }
            }
            return unresolved(candidates);
        }

        private WiringResolution unresolved(List<WiringBeanRegistration> candidates) {
            List<BeanId> ids = candidates.stream().map(WiringBeanRegistration::beanId).distinct()
                    .sorted(Comparator.comparing(BeanId::value)).toList();
            return new WiringResolution(Optional.empty(), ids);
        }

        private List<WiringBeanRegistration> registrationsByName(String name) {
            return byName.getOrDefault(name, List.of()).stream().distinct().toList();
        }

        private boolean isAssignable(TypeId candidate, TypeId required) {
            if (candidate.equals(required)) {
                return true;
            }
            Set<String> visited = new HashSet<>();
            List<String> pending = new ArrayList<>();
            pending.add(candidate.binaryName());
            while (!pending.isEmpty()) {
                String current = pending.remove(pending.size() - 1);
                if (!visited.add(current)) {
                    continue;
                }
                for (String parent : hierarchy.getOrDefault(current, Set.of())) {
                    if (parent.equals(required.binaryName())) {
                        return true;
                    }
                    pending.add(parent);
                }
            }
            return false;
        }
    }

    private static final class BeanRegistry {
        private final Map<BeanId, RawBean> byId = new LinkedHashMap<>();
        private final Map<String, List<RawBean>> directNames = new LinkedHashMap<>();
        private final Map<String, AliasDeclaration> aliases = new LinkedHashMap<>();
        private final Set<String> conflictingNames = new HashSet<>();
        private final List<Diagnostic> diagnostics;

        private BeanRegistry(List<RawBean> beans, java.util.Collection<ParsedDocument> documents,
                             List<Diagnostic> diagnostics) {
            this.diagnostics = diagnostics;
            for (RawBean bean : beans) {
                RawBean previous = byId.putIfAbsent(bean.beanId(), bean);
                if (previous != null) {
                    conflictingNames.add(bean.beanId().beanId());
                    diagnostics.add(diagnostic(DiagnosticCode.XML_DUPLICATE_BEAN, bean.file(), bean.line(),
                            "duplicate bean id: " + bean.beanId().beanId()));
                    continue;
                }
                addDirect(bean.beanId().beanId(), bean);
                bean.inlineAliases().forEach(alias -> addDirect(alias, bean));
            }
            documents.stream().sorted(Comparator.comparing(ParsedDocument::context))
                    .flatMap(document -> document.aliases().stream()).forEach(this::addAlias);
            directNames.forEach((name, definitions) -> {
                if (definitions.size() > 1) {
                    conflictingNames.add(name);
                    RawBean bean = definitions.get(definitions.size() - 1);
                    diagnostics.add(diagnostic(DiagnosticCode.XML_DUPLICATE_BEAN, bean.file(), bean.line(),
                            "conflicting bean name or alias: " + name));
                }
            });
        }

        List<RawBean> uniqueBeans() {
            return List.copyOf(byId.values());
        }

        Optional<RawBean> byId(BeanId id) {
            return Optional.ofNullable(byId.get(id));
        }

        Optional<RawBean> resolve(String name, Set<String> visiting) {
            if (name == null || name.isBlank() || conflictingNames.contains(name)) {
                return Optional.empty();
            }
            List<RawBean> direct = directNames.getOrDefault(name, List.of());
            if (direct.size() == 1) {
                return Optional.of(direct.get(0));
            }
            AliasDeclaration alias = aliases.get(name);
            if (alias == null) {
                return Optional.empty();
            }
            if (!visiting.add(name)) {
                diagnostics.add(diagnostic(DiagnosticCode.XML_ALIAS_CYCLE, alias.file(), alias.line(),
                        "alias cycle includes " + name));
                return Optional.empty();
            }
            Optional<RawBean> resolved = resolve(alias.targetName(), visiting);
            visiting.remove(name);
            return resolved;
        }

        Map<BeanId, Set<String>> resolvedAliases() {
            Map<BeanId, Set<String>> result = new HashMap<>();
            directNames.forEach((name, definitions) -> {
                if (definitions.size() == 1 && !name.equals(definitions.get(0).beanId().beanId())) {
                    result.computeIfAbsent(definitions.get(0).beanId(), ignored -> new LinkedHashSet<>()).add(name);
                }
            });
            aliases.forEach((name, declaration) -> {
                Optional<RawBean> target = resolve(name, new LinkedHashSet<>());
                if (target.isPresent()) {
                    result.computeIfAbsent(target.orElseThrow().beanId(), ignored -> new LinkedHashSet<>()).add(name);
                } else if (!hasAliasCycle(name)) {
                    diagnostics.add(diagnostic(DiagnosticCode.XML_ALIAS_TARGET_MISSING,
                            declaration.file(), declaration.line(), "alias target not found or ambiguous: "
                                    + declaration.targetName()));
                }
            });
            return result;
        }

        private void addDirect(String name, RawBean bean) {
            if (!name.isBlank()) {
                directNames.computeIfAbsent(name, ignored -> new ArrayList<>()).add(bean);
            }
        }

        private void addAlias(AliasDeclaration alias) {
            if (alias.alias().isBlank() || alias.targetName().isBlank()) {
                diagnostics.add(diagnostic(DiagnosticCode.XML_ALIAS_TARGET_MISSING,
                        alias.file(), alias.line(), "alias requires name and alias attributes"));
                return;
            }
            if (directNames.containsKey(alias.alias())) {
                conflictingNames.add(alias.alias());
                diagnostics.add(diagnostic(DiagnosticCode.XML_DUPLICATE_BEAN, alias.file(), alias.line(),
                        "alias conflicts with bean name: " + alias.alias()));
                return;
            }
            AliasDeclaration previous = aliases.putIfAbsent(alias.alias(), alias);
            if (previous != null && !previous.targetName().equals(alias.targetName())) {
                conflictingNames.add(alias.alias());
                diagnostics.add(diagnostic(DiagnosticCode.XML_DUPLICATE_BEAN, alias.file(), alias.line(),
                        "conflicting alias: " + alias.alias()));
            }
        }

        private boolean hasAliasCycle(String name) {
            Set<String> visited = new HashSet<>();
            String current = name;
            while (aliases.containsKey(current)) {
                if (!visited.add(current)) {
                    return true;
                }
                current = aliases.get(current).targetName();
            }
            return false;
        }
    }
}
