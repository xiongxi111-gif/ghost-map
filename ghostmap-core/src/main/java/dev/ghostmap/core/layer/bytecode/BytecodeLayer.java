package dev.ghostmap.core.layer.bytecode;

import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.DiagnosticSeverity;
import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.Layer;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.FieldId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.NumberValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** ASM-based class directory and JAR analysis that never loads target classes. */
public final class BytecodeLayer implements Layer {
    @Override
    public LayerId id() {
        return LayerId.BYTECODE;
    }

    @Override
    public LayerFacts analyse(LayerInput input) {
        if (input == null) {
            throw new NullPointerException("input");
        }
        List<FactNode> nodes = new ArrayList<>();
        List<EdgeFact> edges = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Path root : input.classRoots()) {
            if (!Files.exists(root)) {
                diagnostics.add(diagnostic(DiagnosticCode.CLASS_ROOT_NOT_FOUND, root.toString(),
                        "class root does not exist"));
            } else if (Files.isDirectory(root)) {
                readDirectory(root, nodes, edges, diagnostics);
            } else if (Files.isRegularFile(root) && root.getFileName().toString().toLowerCase(Locale.ROOT)
                    .endsWith(".jar")) {
                readJar(root, nodes, edges, diagnostics);
            } else {
                diagnostics.add(diagnostic(DiagnosticCode.CLASS_ROOT_UNSUPPORTED, root.toString(),
                        "class root must be a directory or JAR"));
            }
        }
        return new LayerFacts(LayerId.BYTECODE, nodes, edges, diagnostics);
    }

    private static void readDirectory(Path root, List<FactNode> nodes, List<EdgeFact> edges,
                                      List<Diagnostic> diagnostics) {
        List<Path> classFiles;
        try (Stream<Path> paths = Files.walk(root)) {
            classFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .filter(path -> !skipClass(path.getFileName().toString(), ""))
                    .sorted()
                    .toList();
        } catch (IOException | UncheckedIOException | SecurityException exception) {
            diagnostics.add(diagnostic(DiagnosticCode.CLASS_ROOT_READ_ERROR, root.toString(),
                    "cannot enumerate class root: " + detail(exception)));
            return;
        }
        String rootName = root.getFileName() == null ? "classes" : root.getFileName().toString();
        for (Path classFile : classFiles) {
            String relative = portable(root.relativize(classFile));
            String display = rootName + "/" + relative;
            try {
                analyseClass(new ClassArtifact(display, Files.readAllBytes(classFile)), nodes, edges);
            } catch (IOException exception) {
                diagnostics.add(diagnostic(DiagnosticCode.CLASS_FILE_READ_ERROR, display,
                        "cannot read class file: " + detail(exception)));
            } catch (RuntimeException exception) {
                diagnostics.add(diagnostic(DiagnosticCode.INVALID_CLASS_FILE, display,
                        "invalid class file: " + detail(exception)));
            }
        }
    }

    private static void readJar(Path jar, List<FactNode> nodes, List<EdgeFact> edges,
                                List<Diagnostic> diagnostics) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .filter(entry -> !skipClass(entry.getName(), entry.getName()))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : entries) {
                String display = jar.getFileName() + "!/" + entry.getName();
                try (InputStream stream = zip.getInputStream(entry)) {
                    analyseClass(new ClassArtifact(display, stream.readAllBytes()), nodes, edges);
                } catch (IOException exception) {
                    diagnostics.add(diagnostic(DiagnosticCode.CLASS_FILE_READ_ERROR, display,
                            "cannot read JAR entry: " + detail(exception)));
                } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException exception) {
                    diagnostics.add(diagnostic(DiagnosticCode.INVALID_CLASS_FILE, display,
                            "invalid class file: " + detail(exception)));
                }
            }
        } catch (IOException | SecurityException exception) {
            diagnostics.add(diagnostic(DiagnosticCode.CLASS_ROOT_READ_ERROR, jar.toString(),
                    "cannot read JAR: " + detail(exception)));
        }
    }

    private static boolean skipClass(String fileName, String entryName) {
        return fileName.endsWith("module-info.class") || fileName.endsWith("package-info.class")
                || entryName.startsWith("META-INF/versions/");
    }

    private static void analyseClass(ClassArtifact artifact, List<FactNode> nodes, List<EdgeFact> edges) {
        OffsetClassReader reader = new OffsetClassReader(artifact.bytes());
        ClassCollector collector = new ClassCollector(reader, artifact.displayPath());
        reader.accept(collector, ClassReader.SKIP_FRAMES);
        nodes.addAll(collector.nodes());
        edges.addAll(collector.edges());
    }

    private static SymbolId canonical(CanonicalId id) {
        return new SymbolId.Canonical(id);
    }

    private static TypeId typeId(String internalName) {
        return new TypeId(internalName.replace('/', '.'));
    }

    private static Optional<SymbolId> methodTarget(String internalOwner, String name, String descriptor) {
        if (internalOwner.startsWith("[")) {
            return Optional.empty();
        }
        try {
            return Optional.of(canonical(new MethodId(internalOwner.replace('/', '.'), name, descriptor)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Map<String, JsonValue> attributes(int access, List<String> annotations, boolean generated,
                                                     Set<String> generationReasons, String classFile,
                                                     Map<String, JsonValue> extras) {
        Map<String, JsonValue> attrs = new LinkedHashMap<>();
        attrs.put("access", new NumberValue(BigDecimal.valueOf(access)));
        attrs.put("annotations", strings(annotations));
        attrs.put("classFile", new StringValue(classFile));
        attrs.put("generated", new BooleanValue(generated));
        attrs.put("generationReasons", strings(generationReasons.stream().sorted().toList()));
        attrs.put("synthetic", new BooleanValue((access & Opcodes.ACC_SYNTHETIC) != 0));
        attrs.putAll(extras);
        return Map.copyOf(attrs);
    }

    private static ArrayValue strings(List<String> values) {
        return new ArrayValue(values.stream().map(value -> (JsonValue) new StringValue(value)).toList());
    }

    private static Diagnostic diagnostic(DiagnosticCode code, String file, String message) {
        return new Diagnostic(code, DiagnosticSeverity.ERROR, file.replace('\\', '/'), 0, message);
    }

    private static String detail(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String portable(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private record ClassArtifact(String displayPath, byte[] bytes) {
        private ClassArtifact {
            if (displayPath == null || bytes == null) {
                throw new NullPointerException("class artifact fields must not be null");
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static final class OffsetClassReader extends ClassReader {
        private int instructionOffset = -1;

        private OffsetClassReader(byte[] bytes) {
            super(bytes);
        }

        @Override
        protected void readBytecodeInstructionOffset(int bytecodeOffset) {
            instructionOffset = bytecodeOffset;
        }

        int instructionOffset() {
            if (instructionOffset < 0) {
                throw new IllegalStateException("ASM did not report an instruction offset");
            }
            return instructionOffset;
        }
    }

    private static final class ClassCollector extends ClassVisitor {
        private final OffsetClassReader reader;
        private final String classFile;
        private final List<FactNode> memberNodes = new ArrayList<>();
        private final List<EdgeFact> edges = new ArrayList<>();
        private final List<String> annotations = new ArrayList<>();
        private final Map<String, String> recordComponents = new HashMap<>();
        private final Set<String> registryFields = new LinkedHashSet<>();
        private String internalName;
        private TypeId type;
        private int access;
        private String sourceFile;
        private String superName;
        private List<String> interfaces = List.of();

        private ClassCollector(OffsetClassReader reader, String classFile) {
            super(Opcodes.ASM9);
            this.reader = reader;
            this.classFile = classFile;
        }

        @Override
        public void visit(int version, int classAccess, String name, String signature, String superclass,
                          String[] implementedInterfaces) {
            access = classAccess;
            internalName = name;
            type = typeId(name);
            superName = superclass;
            interfaces = List.of(implementedInterfaces.clone());
            SymbolId from = canonical(type);
            boolean interfaceType = (classAccess & Opcodes.ACC_INTERFACE) != 0;
            if (superclass != null && !interfaceType) {
                edges.add(new BytecodeHierarchyFact(EdgeKind.EXTENDS, from, canonical(typeId(superclass)), classFile));
            }
            EdgeKind interfaceEdge = interfaceType ? EdgeKind.EXTENDS : EdgeKind.IMPLEMENTS;
            for (String implementedInterface : implementedInterfaces) {
                edges.add(new BytecodeHierarchyFact(interfaceEdge, from, canonical(typeId(implementedInterface)),
                        classFile));
            }
        }

        @Override
        public void visitSource(String source, String debug) {
            sourceFile = source;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotations.add(Type.getType(descriptor).getClassName());
            return null;
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            recordComponents.put(name, descriptor);
            return null;
        }

        @Override
        public FieldVisitor visitField(int fieldAccess, String name, String descriptor, String signature,
                                       Object value) {
            if (isRegistryField(name, descriptor)) {
                registryFields.add(name);
            }
            return new FieldCollector(fieldAccess, name, descriptor,
                    node -> memberNodes.add(node));
        }

        @Override
        public MethodVisitor visitMethod(int methodAccess, String name, String descriptor, String signature,
                                         String[] exceptions) {
            return new MethodCollector(methodAccess, name, descriptor,
                    node -> memberNodes.add(node));
        }

        @Override
        public void visitEnd() {
            Set<String> reasons = generationReasons(access, annotations, false, false);
            Map<String, JsonValue> extras = new LinkedHashMap<>();
            extras.put("internalName", new StringValue(internalName));
            extras.put("interfaces", strings(interfaces.stream().map(name -> name.replace('/', '.')).toList()));
            extras.put("record", new BooleanValue((access & Opcodes.ACC_RECORD) != 0));
            if (sourceFile != null) {
                extras.put("sourceFile", new StringValue(sourceFile));
            }
            if (superName != null) {
                extras.put("superName", new StringValue(superName.replace('/', '.')));
            }
            String binaryName = type.binaryName();
            int simpleNameStart = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$')) + 1;
            FactNode classNode = new FactNode(canonical(type), NodeKind.TYPE,
                    binaryName.substring(simpleNameStart), Optional.empty(),
                    attributes(access, annotations, !reasons.isEmpty(), reasons, classFile, extras), Optional.empty());
            memberNodes.add(0, classNode);
        }

        List<FactNode> nodes() {
            return List.copyOf(memberNodes);
        }

        List<EdgeFact> edges() {
            return List.copyOf(edges);
        }

        private final class FieldCollector extends FieldVisitor {
            private final int fieldAccess;
            private final String name;
            private final String descriptor;
            private final Consumer<FactNode> sink;
            private final List<String> fieldAnnotations = new ArrayList<>();

            private FieldCollector(int fieldAccess, String name, String descriptor, Consumer<FactNode> sink) {
                super(Opcodes.ASM9);
                this.fieldAccess = fieldAccess;
                this.name = name;
                this.descriptor = descriptor;
                this.sink = sink;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                fieldAnnotations.add(Type.getType(annotationDescriptor).getClassName());
                return null;
            }

            @Override
            public void visitEnd() {
                boolean recordGenerated = (access & Opcodes.ACC_RECORD) != 0
                        && descriptor.equals(recordComponents.get(name));
                Set<String> reasons = generationReasons(fieldAccess, fieldAnnotations, false, recordGenerated);
                Map<String, JsonValue> extras = Map.of(
                        "descriptor", new StringValue(descriptor),
                        "recordComponentBackingField", new BooleanValue(recordGenerated));
                sink.accept(new FactNode(canonical(new FieldId(type.binaryName(), name, descriptor)), NodeKind.FIELD,
                        name, Optional.of(type), attributes(fieldAccess, fieldAnnotations, !reasons.isEmpty(), reasons,
                        classFile, extras), Optional.empty()));
            }
        }

        private final class MethodCollector extends MethodVisitor {
            private final int methodAccess;
            private final String name;
            private final String descriptor;
            private final Consumer<FactNode> sink;
            private final List<String> methodAnnotations = new ArrayList<>();
            private final MethodId methodId;
            private int currentLine;
            private int firstLine = Integer.MAX_VALUE;
            private int lastLine = Integer.MIN_VALUE;

            private MethodCollector(int methodAccess, String name, String descriptor, Consumer<FactNode> sink) {
                super(Opcodes.ASM9);
                this.methodAccess = methodAccess;
                this.name = name;
                this.descriptor = descriptor;
                this.sink = sink;
                methodId = new MethodId(type.binaryName(), name, descriptor);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                methodAnnotations.add(Type.getType(annotationDescriptor).getClassName());
                return null;
            }

            @Override
            public void visitLineNumber(int line, Label start) {
                currentLine = line;
                firstLine = Math.min(firstLine, line);
                lastLine = Math.max(lastLine, line);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String invokedName, String invokedDescriptor,
                                        boolean isInterface) {
                JvmInvocationKind invocationKind = invocationKind(opcode);
                int offset = reader.instructionOffset();
                OptionalInt line = currentLine > 0 ? OptionalInt.of(currentLine) : OptionalInt.empty();
                BytecodeDynamicKind dynamic = dynamicKind(owner, invokedName);
                DispatchKind dispatch = dispatch(invocationKind, dynamic, "");
                Evidence.Bytecode evidence = new Evidence.Bytecode(classFile, offset,
                        invocationKind.name().toLowerCase(Locale.ROOT), owner, invokedName, invokedDescriptor);
                edges.add(new BytecodeCallFact(canonical(methodId), methodTarget(owner, invokedName, invokedDescriptor),
                        new SiteId(methodId, offset), invocationKind, owner, invokedName, invokedDescriptor, evidence,
                        line, Optional.ofNullable(sourceFile), dynamic, dispatch, Optional.empty()));
            }

            @Override
            public void visitInvokeDynamicInsn(String invokedName, String invokedDescriptor,
                                               Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                int offset = reader.instructionOffset();
                OptionalInt line = currentLine > 0 ? OptionalInt.of(currentLine) : OptionalInt.empty();
                String owner = bootstrapMethodHandle.getOwner();
                String bootstrap = owner + '.' + bootstrapMethodHandle.getName()
                        + bootstrapMethodHandle.getDesc();
                Optional<SymbolId> target = implementationTarget(bootstrapMethodArguments);
                BytecodeDynamicKind dynamic = BytecodeDynamicKind.INVOKEDYNAMIC;
                DispatchKind dispatch = dispatch(JvmInvocationKind.INVOKEDYNAMIC, dynamic, owner);
                Evidence.Bytecode evidence = new Evidence.Bytecode(classFile, offset, "invokedynamic", owner,
                        invokedName, invokedDescriptor);
                edges.add(new BytecodeCallFact(canonical(methodId), target, new SiteId(methodId, offset),
                        JvmInvocationKind.INVOKEDYNAMIC, owner, invokedName, invokedDescriptor, evidence, line,
                        Optional.ofNullable(sourceFile), dynamic, dispatch, Optional.of(bootstrap)));
            }

            @Override
            public void visitEnd() {
                boolean recordGenerated = isGeneratedRecordMethod(name, descriptor);
                boolean bridge = (methodAccess & Opcodes.ACC_BRIDGE) != 0;
                Set<String> reasons = generationReasons(methodAccess, methodAnnotations, bridge, recordGenerated);
                Map<String, JsonValue> extras = new LinkedHashMap<>();
                extras.put("abstract", new BooleanValue((methodAccess & Opcodes.ACC_ABSTRACT) != 0));
                extras.put("bridge", new BooleanValue(bridge));
                extras.put("descriptor", new StringValue(descriptor));
                extras.put("native", new BooleanValue((methodAccess & Opcodes.ACC_NATIVE) != 0));
                Optional<SourceLocation> location = Optional.empty();
                if (sourceFile != null && firstLine != Integer.MAX_VALUE) {
                    location = Optional.of(new SourceLocation(sourceFile, firstLine, lastLine));
                }
                sink.accept(new FactNode(canonical(methodId), NodeKind.METHOD, name, Optional.of(type),
                        attributes(methodAccess, methodAnnotations, !reasons.isEmpty(), reasons, classFile, extras),
                        location));
            }
        }

        private Optional<SymbolId> implementationTarget(Object[] bootstrapArguments) {
            for (Object argument : bootstrapArguments) {
                if (argument instanceof Handle handle && handle.getDesc().startsWith("(")) {
                    Optional<SymbolId> target = methodTarget(handle.getOwner(), handle.getName(), handle.getDesc());
                    if (target.isPresent()) {
                        return target;
                    }
                }
            }
            return Optional.empty();
        }

        private boolean isGeneratedRecordMethod(String name, String descriptor) {
            if ((access & Opcodes.ACC_RECORD) == 0) {
                return false;
            }
            String componentDescriptor = recordComponents.get(name);
            if (componentDescriptor != null && descriptor.equals("()" + componentDescriptor)) {
                return true;
            }
            return ("equals".equals(name) && "(Ljava/lang/Object;)Z".equals(descriptor))
                    || ("hashCode".equals(name) && "()I".equals(descriptor))
                    || ("toString".equals(name) && "()Ljava/lang/String;".equals(descriptor));
        }

        private boolean isRegistryField(String name, String descriptor) {
            if (!"Ljava/util/Map;".equals(descriptor)) {
                return false;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.contains("handler") || lower.contains("registry") || lower.contains("dispatch")
                    || lower.contains("route");
        }

        private BytecodeDynamicKind dynamicKind(String owner, String methodName) {
            if ("getBean".equals(methodName)
                    && (owner.contains("ApplicationContext") || owner.contains("BeanFactory"))) {
                return BytecodeDynamicKind.GET_BEAN;
            }
            if ("java/lang/reflect/Method".equals(owner) && "invoke".equals(methodName)) {
                return BytecodeDynamicKind.REFLECTION;
            }
            if ("java/lang/Class".equals(owner) && "forName".equals(methodName)) {
                return BytecodeDynamicKind.CLASS_LOADING;
            }
            if (owner.startsWith("java/lang/invoke/MethodHandle")
                    || owner.startsWith("java/lang/invoke/MethodHandles$Lookup")) {
                return BytecodeDynamicKind.METHOD_HANDLE;
            }
            if ("java/util/Map".equals(owner) && "get".equals(methodName) && !registryFields.isEmpty()) {
                return BytecodeDynamicKind.REGISTRY;
            }
            return BytecodeDynamicKind.NONE;
        }
    }

    private static Set<String> generationReasons(int access, List<String> annotations,
                                                 boolean bridge, boolean recordGenerated) {
        Set<String> reasons = new LinkedHashSet<>();
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
            reasons.add("SYNTHETIC");
        }
        if (bridge) {
            reasons.add("BRIDGE");
        }
        if (annotations.stream().anyMatch(annotation -> annotation.equals("lombok.Generated")
                || annotation.endsWith(".Generated"))) {
            reasons.add("GENERATED_ANNOTATION");
        }
        if (recordGenerated) {
            reasons.add("RECORD_MEMBER");
        }
        return Set.copyOf(reasons);
    }

    private static JvmInvocationKind invocationKind(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> JvmInvocationKind.INVOKEVIRTUAL;
            case Opcodes.INVOKEINTERFACE -> JvmInvocationKind.INVOKEINTERFACE;
            case Opcodes.INVOKESTATIC -> JvmInvocationKind.INVOKESTATIC;
            case Opcodes.INVOKESPECIAL -> JvmInvocationKind.INVOKESPECIAL;
            default -> throw new IllegalArgumentException("unsupported method invocation opcode: " + opcode);
        };
    }

    private static DispatchKind dispatch(JvmInvocationKind invocation, BytecodeDynamicKind dynamic, String owner) {
        return switch (dynamic) {
            case GET_BEAN, CLASS_LOADING, METHOD_HANDLE, INVOKEDYNAMIC ->
                    invocation == JvmInvocationKind.INVOKEDYNAMIC && owner.contains("LambdaMetafactory")
                            ? DispatchKind.LAMBDA : DispatchKind.DYNAMIC;
            case REFLECTION -> DispatchKind.REFLECTION;
            case REGISTRY -> DispatchKind.REGISTRY;
            case NONE -> switch (invocation) {
                case INVOKEINTERFACE -> DispatchKind.INTERFACE;
                case INVOKEVIRTUAL -> DispatchKind.VIRTUAL;
                case INVOKESTATIC, INVOKESPECIAL -> DispatchKind.STATIC;
                case INVOKEDYNAMIC -> DispatchKind.DYNAMIC;
            };
        };
    }
}
