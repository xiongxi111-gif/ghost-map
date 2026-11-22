package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.DiagnosticSeverity;
import dev.ghostmap.model.CanonicalId.BeanId;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

final class XmlBeanDocumentParser {
    private final Consumer<Diagnostic> diagnostics;

    XmlBeanDocumentParser(Consumer<Diagnostic> diagnostics) {
        this.diagnostics = diagnostics;
    }

    Optional<ParsedDocument> parse(Path path, String context) {
        String xml;
        try {
            xml = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            diagnostics.accept(diagnostic(DiagnosticCode.RESOURCE_ROOT_READ_ERROR, context, 0,
                    "cannot read XML resource: " + detail(exception)));
            return Optional.empty();
        }
        if (xml.contains("<!DOCTYPE")) {
            diagnostics.accept(diagnostic(DiagnosticCode.XML_SECURITY_VIOLATION, context, 1,
                    "DOCTYPE declarations are forbidden"));
            return Optional.empty();
        }

        List<BeanBuilder> beanBuilders = new ArrayList<>();
        List<AliasDeclaration> aliases = new ArrayList<>();
        List<ImportDeclaration> imports = new ArrayList<>();
        Deque<BeanBuilder> beanStack = new ArrayDeque<>();
        Deque<PointFrame> pointStack = new ArrayDeque<>();
        XMLStreamReader reader = null;
        try {
            XMLInputFactory factory = secureFactory();
            reader = factory.createXMLStreamReader(new StringReader(xml));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw new SecurityXmlException("DTD and entity references are forbidden");
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String element = reader.getLocalName();
                    int line = line(reader);
                    switch (element) {
                        case "bean" -> startBean(reader, context, line, beanStack, pointStack, beanBuilders);
                        case "property" -> startProperty(reader, context, line, beanStack, pointStack);
                        case "constructor-arg" -> startConstructorArgument(reader, context, line,
                                beanStack, pointStack);
                        case "ref", "idref" -> addNestedReference(reader, context, line, beanStack, pointStack);
                        case "alias" -> {
                            if (beanStack.isEmpty()) {
                                aliases.add(new AliasDeclaration(attribute(reader, "name"),
                                        attribute(reader, "alias"), context, line));
                            }
                        }
                        case "import" -> {
                            if (beanStack.isEmpty()) {
                                imports.add(new ImportDeclaration(attribute(reader, "resource"), context, line));
                            }
                        }
                        default -> {
                            // Other Spring elements can contain beans/refs and are traversed safely.
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String element = reader.getLocalName();
                    if ("bean".equals(element) && !beanStack.isEmpty()) {
                        beanStack.pop();
                    } else if (("property".equals(element) || "constructor-arg".equals(element))
                            && !pointStack.isEmpty() && pointStack.peek().element().equals(element)) {
                        pointStack.pop();
                    }
                }
            }
            List<RawBean> beans = beanBuilders.stream().map(BeanBuilder::snapshot).toList();
            return Optional.of(new ParsedDocument(path, context, beans, aliases, imports));
        } catch (SecurityXmlException exception) {
            diagnostics.accept(diagnostic(DiagnosticCode.XML_SECURITY_VIOLATION, context,
                    reader == null ? 1 : line(reader), exception.getMessage()));
        } catch (XMLStreamException | RuntimeException exception) {
            diagnostics.accept(diagnostic(DiagnosticCode.XML_MALFORMED, context,
                    reader == null ? 1 : line(reader), "malformed XML: " + detail(exception)));
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Parsing already completed or produced a diagnostic.
                }
            }
        }
        return Optional.empty();
    }

    private void startBean(XMLStreamReader reader, String context, int line,
                           Deque<BeanBuilder> beanStack, Deque<PointFrame> pointStack,
                           List<BeanBuilder> beans) {
        BeanBuilder parent = beanStack.peek();
        String explicitId = attribute(reader, "id");
        List<String> names = splitNames(attribute(reader, "name"));
        String id = explicitId;
        List<String> inlineAliases = new ArrayList<>(names);
        if (id.isBlank() && !names.isEmpty()) {
            id = names.get(0);
            inlineAliases.remove(0);
        }
        boolean inner = parent != null;
        if (id.isBlank() && inner) {
            id = parent.id() + "$inner$" + line + '$' + parent.nextInnerOrdinal();
        }
        boolean emit = !id.isBlank();
        if (!emit) {
            id = "$invalid$" + line;
            diagnostics.accept(diagnostic(DiagnosticCode.XML_MALFORMED, context, line,
                    "top-level bean requires id or name"));
        }
        BeanBuilder bean;
        try {
            bean = new BeanBuilder(new BeanId(context, id), inlineAliases, attribute(reader, "class"),
                    attribute(reader, "scope"), Boolean.parseBoolean(attribute(reader, "abstract")),
                    attribute(reader, "parent"), context, line, inner, emit);
        } catch (IllegalArgumentException exception) {
            diagnostics.accept(diagnostic(DiagnosticCode.XML_MALFORMED, context, line,
                    "invalid bean id"));
            bean = new BeanBuilder(new BeanId(context, "$invalid$" + line), List.of(), "", "", false,
                    "", context, line, inner, false);
        }
        if (emit) {
            beans.add(bean);
        }
        if (parent != null && parent.emit()) {
            PointFrame frame = pointFor(parent, pointStack);
            parent.setTarget(frame.key(), frame.point(), Optional.empty(), Optional.of(bean.beanId()),
                    Optional.empty(), context, line);
        }
        beanStack.push(bean);
    }

    private static void startProperty(XMLStreamReader reader, String context, int line,
                                      Deque<BeanBuilder> beanStack, Deque<PointFrame> pointStack) {
        BeanBuilder bean = beanStack.peek();
        if (bean == null) {
            return;
        }
        String name = attribute(reader, "name");
        WiringInjectionPoint point = new WiringInjectionPoint(InjectionKind.PROPERTY,
                optional(name), OptionalInt.empty());
        String key = "property:" + name;
        bean.setTarget(key, point, optional(attribute(reader, "ref")), Optional.empty(),
                optional(attribute(reader, "value")), context, line);
        pointStack.push(new PointFrame("property", bean, key, point));
    }

    private static void startConstructorArgument(XMLStreamReader reader, String context, int line,
                                                 Deque<BeanBuilder> beanStack,
                                                 Deque<PointFrame> pointStack) {
        BeanBuilder bean = beanStack.peek();
        if (bean == null) {
            return;
        }
        String name = attribute(reader, "name");
        String indexValue = attribute(reader, "index");
        OptionalInt index = parseIndex(indexValue);
        int ordinal = bean.nextConstructorOrdinal();
        String key = !name.isBlank() ? "constructor:name:" + name
                : index.isPresent() ? "constructor:index:" + index.orElseThrow()
                : "constructor:ordinal:" + ordinal;
        WiringInjectionPoint point = new WiringInjectionPoint(InjectionKind.CONSTRUCTOR_ARGUMENT,
                optional(name), index);
        bean.setTarget(key, point, optional(attribute(reader, "ref")), Optional.empty(),
                optional(attribute(reader, "value")), context, line);
        pointStack.push(new PointFrame("constructor-arg", bean, key, point));
    }

    private static void addNestedReference(XMLStreamReader reader, String context, int line,
                                           Deque<BeanBuilder> beanStack, Deque<PointFrame> pointStack) {
        BeanBuilder bean = beanStack.peek();
        if (bean == null) {
            return;
        }
        String target = firstNonBlank(attribute(reader, "bean"), attribute(reader, "local"),
                attribute(reader, "parent"));
        PointFrame frame = pointFor(bean, pointStack);
        bean.setTarget(frame.key(), frame.point(), optional(target), Optional.empty(), Optional.empty(), context, line);
    }

    private static PointFrame pointFor(BeanBuilder bean, Deque<PointFrame> pointStack) {
        if (!pointStack.isEmpty() && pointStack.peek().owner() == bean) {
            return pointStack.peek();
        }
        int ordinal = bean.nextReferenceOrdinal();
        WiringInjectionPoint point = new WiringInjectionPoint(InjectionKind.REF, Optional.empty(),
                OptionalInt.empty());
        return new PointFrame("ref", bean, "ref:" + ordinal, point);
    }

    private static XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("external XML resources are forbidden");
        });
        return factory;
    }

    private static String attribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null ? "" : value.strip();
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static OptionalInt parseIndex(String value) {
        if (value.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            int index = Integer.parseInt(value);
            return index >= 0 ? OptionalInt.of(index) : OptionalInt.empty();
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static List<String> splitNames(String names) {
        if (names.isBlank()) {
            return List.of();
        }
        return List.of(names.split("[,;\\s]+"));
    }

    private static int line(XMLStreamReader reader) {
        return Math.max(1, reader.getLocation().getLineNumber());
    }

    private static Diagnostic diagnostic(DiagnosticCode code, String file, int line, String message) {
        return new Diagnostic(code, DiagnosticSeverity.ERROR, file, line, message);
    }

    private static String detail(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    record ParsedDocument(Path path, String context, List<RawBean> beans,
                          List<AliasDeclaration> aliases, List<ImportDeclaration> imports) {
        ParsedDocument {
            beans = List.copyOf(beans);
            aliases = List.copyOf(aliases);
            imports = List.copyOf(imports);
        }
    }

    record RawBean(BeanId beanId, List<String> inlineAliases, String declaredClass, String declaredScope,
                   boolean abstractBean, String parentName, String file, int line, boolean inner,
                   boolean emit, Map<String, RawInjection> values) {
        RawBean {
            inlineAliases = List.copyOf(inlineAliases);
            values = Map.copyOf(values);
        }
    }

    record RawInjection(String key, WiringInjectionPoint point, Optional<String> referenceName,
                        Optional<BeanId> directTarget, Optional<String> literalValue, String file, int line) {
    }

    record AliasDeclaration(String targetName, String alias, String file, int line) {
    }

    record ImportDeclaration(String resource, String file, int line) {
    }

    private record PointFrame(String element, BeanBuilder owner, String key, WiringInjectionPoint point) {
    }

    private static final class BeanBuilder {
        private final BeanId beanId;
        private final List<String> aliases;
        private final String declaredClass;
        private final String declaredScope;
        private final boolean abstractBean;
        private final String parentName;
        private final String file;
        private final int line;
        private final boolean inner;
        private final boolean emit;
        private final Map<String, RawInjection> values = new LinkedHashMap<>();
        private int innerOrdinal;
        private int constructorOrdinal;
        private int referenceOrdinal;

        private BeanBuilder(BeanId beanId, List<String> aliases, String declaredClass, String declaredScope,
                            boolean abstractBean, String parentName, String file, int line,
                            boolean inner, boolean emit) {
            this.beanId = beanId;
            this.aliases = List.copyOf(aliases);
            this.declaredClass = declaredClass;
            this.declaredScope = declaredScope;
            this.abstractBean = abstractBean;
            this.parentName = parentName;
            this.file = file;
            this.line = line;
            this.inner = inner;
            this.emit = emit;
        }

        RawBean snapshot() {
            return new RawBean(beanId, aliases, declaredClass, declaredScope, abstractBean, parentName,
                    file, line, inner, emit, values);
        }

        void setTarget(String key, WiringInjectionPoint point, Optional<String> reference,
                       Optional<BeanId> directTarget, Optional<String> literalValue,
                       String sourceFile, int sourceLine) {
            values.put(key, new RawInjection(key, point, reference, directTarget, literalValue,
                    sourceFile, sourceLine));
        }

        BeanId beanId() {
            return beanId;
        }

        String id() {
            return beanId.beanId();
        }

        boolean emit() {
            return emit;
        }

        int nextInnerOrdinal() {
            return innerOrdinal++;
        }

        int nextConstructorOrdinal() {
            return constructorOrdinal++;
        }

        int nextReferenceOrdinal() {
            return referenceOrdinal++;
        }
    }

    private static final class SecurityXmlException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SecurityXmlException(String message) {
            super(message);
        }
    }
}
