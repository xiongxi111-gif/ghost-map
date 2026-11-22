package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.DiagnosticSeverity;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.NodeKind;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.beans.Introspector;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Reads Spring and standard injection annotations directly from class files with ASM. */
final class AnnotationWiringScanner {
    private static final Set<String> COMPONENT_ANNOTATIONS = Set.of(
            "Lorg/springframework/stereotype/Component;",
            "Lorg/springframework/stereotype/Service;",
            "Lorg/springframework/stereotype/Repository;",
            "Lorg/springframework/stereotype/Controller;",
            "Lorg/springframework/web/bind/annotation/RestController;",
            "Lorg/springframework/context/annotation/Configuration;",
            "Ljavax/inject/Named;",
            "Ljakarta/inject/Named;");
    private static final String CONFIGURATION = "Lorg/springframework/context/annotation/Configuration;";
    private static final String COMPONENT_SCAN = "Lorg/springframework/context/annotation/ComponentScan;";
    private static final String BEAN = "Lorg/springframework/context/annotation/Bean;";
    private static final String PRIMARY = "Lorg/springframework/context/annotation/Primary;";
    private static final Set<String> AUTOWIRED = Set.of(
            "Lorg/springframework/beans/factory/annotation/Autowired;",
            "Ljavax/inject/Inject;", "Ljakarta/inject/Inject;");
    private static final Set<String> RESOURCE = Set.of(
            "Ljavax/annotation/Resource;", "Ljakarta/annotation/Resource;");
    private static final Set<String> QUALIFIER = Set.of(
            "Lorg/springframework/beans/factory/annotation/Qualifier;",
            "Ljavax/inject/Named;", "Ljakarta/inject/Named;");

    private final List<Diagnostic> diagnostics;

    AnnotationWiringScanner(List<Diagnostic> diagnostics) {
        this.diagnostics = diagnostics;
    }

    ScanResult scan(List<Path> classRoots) {
        List<ClassInfo> classes = readClasses(classRoots);
        Map<String, ClassInfo> byDescriptor = new HashMap<>();
        classes.forEach(info -> byDescriptor.put('L' + info.internalName() + ';', info));
        Set<String> scanPackages = componentScanPackages(classes);
        boolean scansDeclared = classes.stream().flatMap(info -> info.annotations().stream())
                .anyMatch(annotation -> COMPONENT_SCAN.equals(annotation.descriptor()));
        List<AnnotationBean> beans = new ArrayList<>();
        List<ImportResourceRequest> imports = new ArrayList<>();

        for (ClassInfo info : classes) {
            boolean configuration = hasAnnotation(info.annotations(), CONFIGURATION);
            info.annotations().stream().filter(annotation ->
                    "Lorg/springframework/context/annotation/ImportResource;".equals(annotation.descriptor()))
                    .flatMap(annotation -> stringValues(annotation, "value", "locations").stream())
                    .forEach(resource -> imports.add(new ImportResourceRequest(resource, info.classFile(), 1)));
            Optional<AnnotationData> stereotype = stereotype(info.annotations(), byDescriptor);
            boolean inScan = !scansDeclared || configuration || scanPackages.stream()
                    .anyMatch(prefix -> packageName(info.binaryName()).equals(prefix)
                            || packageName(info.binaryName()).startsWith(prefix + '.'));
            if (stereotype.isPresent() && inScan && concrete(info.access())) {
                String name = explicitName(stereotype.orElseThrow()).orElseGet(() ->
                        Introspector.decapitalize(simpleName(info.binaryName())));
                beans.add(classBean(info, name));
            }
            if (configuration) {
                for (MethodInfo method : info.methods()) {
                    annotation(method.annotations(), BEAN).ifPresent(beanAnnotation ->
                            beanMethod(info, method, beanAnnotation).ifPresent(beans::add));
                }
            }
        }

        Map<String, Set<String>> hierarchy = new LinkedHashMap<>();
        classes.forEach(info -> {
            Set<String> parents = new LinkedHashSet<>();
            if (info.superName() != null) {
                parents.add(info.superName().replace('/', '.'));
            }
            info.interfaces().stream().map(name -> name.replace('/', '.')).forEach(parents::add);
            hierarchy.put(info.binaryName(), Set.copyOf(parents));
        });
        List<FactNode> nodes = beans.stream().map(AnnotationBean::node)
                .sorted(Comparator.comparing(node -> node.id().value())).toList();
        List<WiringBindFact> binds = beans.stream().map(AnnotationBean::bind).toList();
        List<WiringBeanRegistration> registrations = beans.stream().map(AnnotationBean::registration).toList();
        List<AnnotationInjectionSite> sites = new ArrayList<>();
        for (AnnotationBean bean : beans) {
            if (bean.classInfo() != null) {
                sites.addAll(injectionSites(bean, bean.classInfo()));
            }
            if (bean.factoryMethod() != null) {
                sites.addAll(factoryMethodSites(bean, bean.factoryMethod()));
            }
        }
        return new ScanResult(nodes, binds, registrations, sites, hierarchy, imports);
    }

    private AnnotationBean classBean(ClassInfo info, String name) {
        BeanId beanId = new BeanId("annotations", name);
        TypeId type = new TypeId(info.binaryName());
        Set<String> qualifiers = qualifiers(info.annotations());
        Set<String> names = Set.of(name);
        Map<String, JsonValue> attrs = beanAttributes(type, name, names, qualifiers,
                hasAnnotation(info.annotations(), PRIMARY), info.classFile(), info.sourceFile(), Optional.empty());
        FactNode node = new FactNode(canonical(beanId), NodeKind.BEAN, name, Optional.empty(), attrs, Optional.empty());
        Evidence.Wiring evidence = new Evidence.Wiring(info.classFile(), 1, beanId);
        WiringBindFact bind = new WiringBindFact(canonical(beanId), canonical(type), evidence, List.of());
        WiringBeanRegistration registration = new WiringBeanRegistration(beanId, type, names, qualifiers,
                hasAnnotation(info.annotations(), PRIMARY));
        return new AnnotationBean(node, bind, registration, info, null);
    }

    private Optional<AnnotationBean> beanMethod(ClassInfo configuration, MethodInfo method,
                                                AnnotationData beanAnnotation) {
        Type returnType = Type.getReturnType(method.descriptor());
        if (returnType.getSort() != Type.OBJECT) {
            diagnostics.add(diagnostic(DiagnosticCode.XML_INVALID_CLASS, configuration.classFile(), method.line(),
                    "@Bean method return type is not a concrete object: " + method.name()));
            return Optional.empty();
        }
        List<String> declaredNames = stringValues(beanAnnotation, "name", "value");
        String name = declaredNames.isEmpty() ? method.name() : declaredNames.get(0);
        Set<String> names = new LinkedHashSet<>(declaredNames);
        names.add(name);
        Set<String> qualifiers = qualifiers(method.annotations());
        BeanId beanId = new BeanId("annotations", name);
        TypeId type = new TypeId(returnType.getClassName());
        boolean primary = hasAnnotation(method.annotations(), PRIMARY);
        Map<String, JsonValue> attrs = beanAttributes(type, name, names, qualifiers, primary,
                configuration.classFile(), configuration.sourceFile(), Optional.of(method.name()));
        FactNode node = new FactNode(canonical(beanId), NodeKind.BEAN, name, Optional.empty(), attrs,
                Optional.empty());
        Evidence.Wiring evidence = new Evidence.Wiring(configuration.classFile(), method.line(), beanId);
        WiringBindFact bind = new WiringBindFact(canonical(beanId), canonical(type), evidence, List.of());
        WiringBeanRegistration registration = new WiringBeanRegistration(beanId, type, names, qualifiers, primary);
        return Optional.of(new AnnotationBean(node, bind, registration, null, method));
    }

    private List<AnnotationInjectionSite> injectionSites(AnnotationBean bean, ClassInfo info) {
        List<AnnotationInjectionSite> sites = new ArrayList<>();
        for (FieldInfo field : info.fields()) {
            Optional<AnnotationData> resource = firstAnnotation(field.annotations(), RESOURCE);
            boolean injected = firstAnnotation(field.annotations(), AUTOWIRED).isPresent() || resource.isPresent();
            if (!injected) {
                continue;
            }
            toTypeId(Type.getType(field.descriptor())).ifPresent(type -> sites.add(new AnnotationInjectionSite(
                    bean.registration().beanId(),
                    new WiringInjectionPoint(InjectionKind.PROPERTY, Optional.of(field.name()), OptionalInt.empty()),
                    type, qualifier(field.annotations()), resourceName(resource, field.name()),
                    explicitResource(resource), info.classFile(), 1)));
        }

        List<MethodInfo> constructors = info.methods().stream().filter(method -> "<init>".equals(method.name()))
                .filter(method -> (method.access() & Opcodes.ACC_SYNTHETIC) == 0).toList();
        for (MethodInfo method : info.methods()) {
            boolean constructor = "<init>".equals(method.name());
            Optional<AnnotationData> resource = firstAnnotation(method.annotations(), RESOURCE);
            boolean annotated = firstAnnotation(method.annotations(), AUTOWIRED).isPresent() || resource.isPresent();
            boolean implicitConstructor = constructor && constructors.size() == 1;
            if (!annotated && !implicitConstructor) {
                continue;
            }
            Type[] arguments = Type.getArgumentTypes(method.descriptor());
            for (int index = 0; index < arguments.length; index++) {
                int parameterIndex = index;
                toTypeId(arguments[index]).ifPresent(type -> {
                    String parameterName = parameterName(method, parameterIndex);
                    InjectionKind kind = constructor ? InjectionKind.CONSTRUCTOR_ARGUMENT : InjectionKind.PROPERTY;
                    Optional<String> pointName = constructor ? Optional.of(parameterName)
                            : Optional.of(setterName(method.name(), parameterName));
                    Optional<AnnotationData> parameterResource = firstAnnotation(
                            method.parameterAnnotations().getOrDefault(parameterIndex, List.of()), RESOURCE);
                    Optional<AnnotationData> effectiveResource = parameterResource.isPresent()
                            ? parameterResource : resource;
                    Optional<String> qualifier = qualifier(method.parameterAnnotations()
                            .getOrDefault(parameterIndex, List.of())).or(() -> qualifier(method.annotations()));
                    sites.add(new AnnotationInjectionSite(bean.registration().beanId(),
                            new WiringInjectionPoint(kind, pointName, OptionalInt.of(parameterIndex)), type, qualifier,
                            resourceName(effectiveResource, setterName(method.name(), parameterName)),
                            explicitResource(effectiveResource), info.classFile(), method.line()));
                });
            }
        }
        return sites;
    }

    private List<AnnotationInjectionSite> factoryMethodSites(AnnotationBean bean, MethodInfo method) {
        List<AnnotationInjectionSite> sites = new ArrayList<>();
        Type[] arguments = Type.getArgumentTypes(method.descriptor());
        for (int index = 0; index < arguments.length; index++) {
            int parameterIndex = index;
            toTypeId(arguments[index]).ifPresent(type -> sites.add(new AnnotationInjectionSite(
                    bean.registration().beanId(),
                    new WiringInjectionPoint(InjectionKind.CONSTRUCTOR_ARGUMENT,
                            Optional.of(parameterName(method, parameterIndex)), OptionalInt.of(parameterIndex)),
                    type, qualifier(method.parameterAnnotations().getOrDefault(parameterIndex, List.of())),
                    Optional.empty(), false, bean.bind().evidence().file(), method.line())));
        }
        return sites;
    }

    private Set<String> componentScanPackages(List<ClassInfo> classes) {
        Set<String> packages = new LinkedHashSet<>();
        for (ClassInfo info : classes) {
            for (AnnotationData scan : info.annotations()) {
                if (!COMPONENT_SCAN.equals(scan.descriptor())) {
                    continue;
                }
                List<String> configured = stringValues(scan, "value", "basePackages");
                if (configured.isEmpty()) {
                    packages.add(packageName(info.binaryName()));
                } else {
                    configured.stream().filter(value -> !value.isBlank()).forEach(packages::add);
                }
                values(scan, "basePackageClasses").stream().filter(Type.class::isInstance).map(Type.class::cast)
                        .map(Type::getClassName).map(AnnotationWiringScanner::packageName).forEach(packages::add);
            }
        }
        return Set.copyOf(packages);
    }

    private Optional<AnnotationData> stereotype(List<AnnotationData> annotations,
                                                Map<String, ClassInfo> byDescriptor) {
        return annotations.stream().filter(annotation -> isStereotype(annotation.descriptor(), byDescriptor,
                new HashSet<>())).findFirst();
    }

    private boolean isStereotype(String descriptor, Map<String, ClassInfo> byDescriptor, Set<String> visiting) {
        if (COMPONENT_ANNOTATIONS.contains(descriptor)) {
            return true;
        }
        if (!visiting.add(descriptor)) {
            return false;
        }
        ClassInfo annotationType = byDescriptor.get(descriptor);
        boolean stereotype = annotationType != null && (annotationType.access() & Opcodes.ACC_ANNOTATION) != 0
                && annotationType.annotations().stream().anyMatch(annotation ->
                isStereotype(annotation.descriptor(), byDescriptor, visiting));
        visiting.remove(descriptor);
        return stereotype;
    }

    private List<ClassInfo> readClasses(List<Path> roots) {
        List<ClassInfo> classes = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                diagnostics.add(diagnostic(DiagnosticCode.CLASS_ROOT_NOT_FOUND, root.toString(), 0,
                        "class root does not exist"));
            } else if (Files.isDirectory(root)) {
                readDirectory(root, classes);
            } else if (Files.isRegularFile(root)
                    && root.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                readJar(root, classes);
            } else {
                diagnostics.add(diagnostic(DiagnosticCode.CLASS_ROOT_UNSUPPORTED, root.toString(), 0,
                        "class root must be a directory or JAR"));
            }
        }
        return classes.stream().sorted(Comparator.comparing(ClassInfo::binaryName)).toList();
    }

    private void readDirectory(Path root, List<ClassInfo> classes) {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .filter(path -> !skip(path.getFileName().toString())).sorted().toList()) {
                String display = root.getFileName() + "/" + root.relativize(file).toString().replace('\\', '/');
                try {
                    classes.add(readClass(Files.readAllBytes(file), display));
                } catch (IOException | RuntimeException exception) {
                    diagnostics.add(diagnostic(DiagnosticCode.INVALID_CLASS_FILE, display, 0,
                            "cannot read annotation metadata: " + detail(exception)));
                }
            }
        } catch (IOException | RuntimeException exception) {
            diagnostics.add(diagnostic(DiagnosticCode.CLASS_ROOT_READ_ERROR, root.toString(), 0,
                    "cannot enumerate class root: " + detail(exception)));
        }
    }

    private void readJar(Path jar, List<ClassInfo> classes) {
        try (JarFile entries = new JarFile(jar.toFile())) {
            List<JarEntry> classEntries = entries.stream().filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .filter(entry -> !entry.getName().startsWith("META-INF/versions/"))
                    .filter(entry -> !skip(entry.getName())).sorted(Comparator.comparing(JarEntry::getName)).toList();
            for (JarEntry entry : classEntries) {
                String display = jar.getFileName() + "!/" + entry.getName();
                try (InputStream input = entries.getInputStream(entry)) {
                    classes.add(readClass(input.readAllBytes(), display));
                } catch (IOException | RuntimeException exception) {
                    diagnostics.add(diagnostic(DiagnosticCode.INVALID_CLASS_FILE, display, 0,
                            "cannot read annotation metadata: " + detail(exception)));
                }
            }
        } catch (IOException | RuntimeException exception) {
            diagnostics.add(diagnostic(DiagnosticCode.CLASS_ROOT_READ_ERROR, jar.toString(), 0,
                    "cannot read class JAR: " + detail(exception)));
        }
    }

    private static ClassInfo readClass(byte[] bytes, String classFile) {
        ClassInfoVisitor visitor = new ClassInfoVisitor(classFile);
        new ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_CODE);
        return visitor.result();
    }

    private static boolean skip(String name) {
        return name.endsWith("module-info.class") || name.endsWith("package-info.class");
    }

    private static Optional<TypeId> toTypeId(Type type) {
        return type.getSort() == Type.OBJECT ? Optional.of(new TypeId(type.getClassName())) : Optional.empty();
    }

    private static Optional<AnnotationData> annotation(List<AnnotationData> annotations, String descriptor) {
        return annotations.stream().filter(annotation -> descriptor.equals(annotation.descriptor())).findFirst();
    }

    private static Optional<AnnotationData> firstAnnotation(List<AnnotationData> annotations,
                                                            Set<String> descriptors) {
        return annotations.stream().filter(annotation -> descriptors.contains(annotation.descriptor())).findFirst();
    }

    private static boolean hasAnnotation(List<AnnotationData> annotations, String descriptor) {
        return annotation(annotations, descriptor).isPresent();
    }

    private static Optional<String> qualifier(List<AnnotationData> annotations) {
        return firstAnnotation(annotations, QUALIFIER).flatMap(AnnotationWiringScanner::explicitName);
    }

    private static Set<String> qualifiers(List<AnnotationData> annotations) {
        Set<String> result = new LinkedHashSet<>();
        annotations.stream().filter(annotation -> QUALIFIER.contains(annotation.descriptor()))
                .map(AnnotationWiringScanner::explicitName).flatMap(Optional::stream).forEach(result::add);
        return Set.copyOf(result);
    }

    private static Optional<String> explicitName(AnnotationData annotation) {
        return stringValues(annotation, "value", "name").stream().filter(value -> !value.isBlank()).findFirst();
    }

    private static Optional<String> resourceName(Optional<AnnotationData> resource, String defaultName) {
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        return explicitName(resource.orElseThrow()).or(() -> Optional.of(defaultName));
    }

    private static boolean explicitResource(Optional<AnnotationData> resource) {
        return resource.flatMap(AnnotationWiringScanner::explicitName).isPresent();
    }

    private static List<String> stringValues(AnnotationData annotation, String... keys) {
        List<String> strings = new ArrayList<>();
        for (String key : keys) {
            for (Object value : values(annotation, key)) {
                if (value instanceof String string) {
                    strings.add(string);
                }
            }
        }
        return strings;
    }

    private static List<Object> values(AnnotationData annotation, String key) {
        Object value = annotation.values().get(key);
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return value == null ? List.of() : List.of(value);
    }

    private static String parameterName(MethodInfo method, int index) {
        return index < method.parameterNames().size() ? method.parameterNames().get(index) : "arg" + index;
    }

    private static String setterName(String method, String fallback) {
        return method.startsWith("set") && method.length() > 3
                ? Introspector.decapitalize(method.substring(3)) : fallback;
    }

    private static String packageName(String binaryName) {
        int separator = binaryName.lastIndexOf('.');
        return separator < 0 ? "" : binaryName.substring(0, separator);
    }

    private static String simpleName(String binaryName) {
        int separator = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
        return binaryName.substring(separator + 1);
    }

    private static boolean concrete(int access) {
        return (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) == 0;
    }

    private static Map<String, JsonValue> beanAttributes(TypeId type, String beanName,
                                                         Set<String> names, Set<String> qualifiers,
                                                         boolean primary, String classFile,
                                                         String sourceFile, Optional<String> factoryMethod) {
        Map<String, JsonValue> attrs = new LinkedHashMap<>();
        attrs.put("aliases", strings(names.stream().filter(name -> !name.isBlank() && !name.equals(beanName))
                .collect(java.util.stream.Collectors.toSet())));
        attrs.put("annotations", new ArrayValue(List.of()));
        attrs.put("class", new StringValue(type.binaryName()));
        attrs.put("classFile", new StringValue(classFile));
        attrs.put("generated", new BooleanValue(false));
        attrs.put("primary", new BooleanValue(primary));
        attrs.put("qualifiers", strings(qualifiers));
        attrs.put("scope", new StringValue("singleton"));
        if (sourceFile != null) {
            attrs.put("sourceFile", new StringValue(sourceFile));
        }
        factoryMethod.ifPresent(method -> attrs.put("factoryMethod", new StringValue(method)));
        return Map.copyOf(attrs);
    }

    private static ArrayValue strings(Set<String> values) {
        return new ArrayValue(values.stream().sorted().map(value -> (JsonValue) new StringValue(value)).toList());
    }

    private static SymbolId canonical(CanonicalId id) {
        return new SymbolId.Canonical(id);
    }

    private static Diagnostic diagnostic(DiagnosticCode code, String file, int line, String message) {
        return new Diagnostic(code, DiagnosticSeverity.ERROR, file.replace('\\', '/'), Math.max(0, line), message);
    }

    private static String detail(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    record ScanResult(List<FactNode> nodes, List<WiringBindFact> binds,
                      List<WiringBeanRegistration> registrations,
                      List<AnnotationInjectionSite> injectionSites,
                      Map<String, Set<String>> hierarchy,
                      List<ImportResourceRequest> importResources) {
        ScanResult {
            nodes = List.copyOf(nodes);
            binds = List.copyOf(binds);
            registrations = List.copyOf(registrations);
            injectionSites = List.copyOf(injectionSites);
            hierarchy = Map.copyOf(hierarchy);
            importResources = List.copyOf(importResources);
        }
    }

    record ImportResourceRequest(String resource, String classFile, int line) {
    }

    private record AnnotationBean(FactNode node, WiringBindFact bind, WiringBeanRegistration registration,
                                  ClassInfo classInfo, MethodInfo factoryMethod) {
    }

    private record AnnotationData(String descriptor, Map<String, Object> values) {
        private AnnotationData {
            values = Map.copyOf(values);
        }
    }

    private record FieldInfo(String name, String descriptor, List<AnnotationData> annotations) {
        private FieldInfo {
            annotations = List.copyOf(annotations);
        }
    }

    private record MethodInfo(int access, String name, String descriptor, List<AnnotationData> annotations,
                              Map<Integer, List<AnnotationData>> parameterAnnotations,
                              List<String> parameterNames, int line) {
        private MethodInfo {
            annotations = List.copyOf(annotations);
            Map<Integer, List<AnnotationData>> copied = new LinkedHashMap<>();
            parameterAnnotations.forEach((index, values) -> copied.put(index, List.copyOf(values)));
            parameterAnnotations = Map.copyOf(copied);
            parameterNames = List.copyOf(parameterNames);
            line = Math.max(1, line);
        }
    }

    private record ClassInfo(int access, String internalName, String binaryName, String superName,
                             List<String> interfaces, List<AnnotationData> annotations,
                             List<FieldInfo> fields, List<MethodInfo> methods,
                             String sourceFile, String classFile) {
        private ClassInfo {
            interfaces = List.copyOf(interfaces);
            annotations = List.copyOf(annotations);
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
        }
    }

    private static final class ClassInfoVisitor extends ClassVisitor {
        private final String classFile;
        private final List<AnnotationData> annotations = new ArrayList<>();
        private final List<FieldInfo> fields = new ArrayList<>();
        private final List<MethodInfo> methods = new ArrayList<>();
        private int access;
        private String internalName;
        private String superName;
        private List<String> interfaces;
        private String sourceFile;

        private ClassInfoVisitor(String classFile) {
            super(Opcodes.ASM9);
            this.classFile = classFile;
        }

        @Override
        public void visit(int version, int classAccess, String name, String signature, String superclass,
                          String[] implementedInterfaces) {
            access = classAccess;
            internalName = name;
            superName = superclass;
            interfaces = List.of(implementedInterfaces.clone());
        }

        @Override
        public void visitSource(String source, String debug) {
            sourceFile = source;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotationCollector(descriptor, annotations::add);
        }

        @Override
        public FieldVisitor visitField(int fieldAccess, String name, String descriptor, String signature,
                                       Object value) {
            return new FieldVisitor(Opcodes.ASM9) {
                private final List<AnnotationData> fieldAnnotations = new ArrayList<>();

                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return annotationCollector(annotationDescriptor, fieldAnnotations::add);
                }

                @Override
                public void visitEnd() {
                    fields.add(new FieldInfo(name, descriptor, fieldAnnotations));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int methodAccess, String name, String descriptor, String signature,
                                         String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                private final List<AnnotationData> methodAnnotations = new ArrayList<>();
                private final Map<Integer, List<AnnotationData>> parameterAnnotations = new LinkedHashMap<>();
                private final List<String> parameterNames = new ArrayList<>();
                private int firstLine;

                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return annotationCollector(annotationDescriptor, methodAnnotations::add);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor,
                                                                  boolean visible) {
                    List<AnnotationData> values = parameterAnnotations.computeIfAbsent(parameter,
                            ignored -> new ArrayList<>());
                    return annotationCollector(annotationDescriptor, values::add);
                }

                @Override
                public void visitParameter(String parameterName, int parameterAccess) {
                    parameterNames.add(parameterName);
                }

                @Override
                public void visitLineNumber(int line, Label start) {
                    if (firstLine == 0) {
                        firstLine = line;
                    }
                }

                @Override
                public void visitEnd() {
                    methods.add(new MethodInfo(methodAccess, name, descriptor, methodAnnotations,
                            parameterAnnotations, parameterNames, firstLine));
                }
            };
        }

        ClassInfo result() {
            return new ClassInfo(access, internalName, internalName.replace('/', '.'), superName, interfaces,
                    annotations, fields, methods, sourceFile, classFile);
        }
    }

    private static AnnotationVisitor annotationCollector(String descriptor, Consumer<AnnotationData> sink) {
        Map<String, Object> values = new LinkedHashMap<>();
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                values.put(name, value);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                List<Object> array = new ArrayList<>();
                values.put(name, array);
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String ignored, Object value) {
                        array.add(value);
                    }
                };
            }

            @Override
            public void visitEnd() {
                sink.accept(new AnnotationData(descriptor, values));
            }
        };
    }
}
