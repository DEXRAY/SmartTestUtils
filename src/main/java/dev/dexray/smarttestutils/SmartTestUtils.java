package dev.dexray.smarttestutils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces a standalone {@code SmartTestUtils.createAsserts(object);} in a Maven test
 * source with AssertJ assertions for the object's current state. Recompile and rerun to
 * execute the generated assertions.
 *
 * <ul>
 *   <li>Only {@code String}, {@code boolean}/{@code Boolean}, enum, {@code LocalDate} and
 *       {@code LocalDateTime} values are asserted; dates are compared via {@code parse("...")}.</li>
 *   <li>Nested objects are followed recursively (at most three property accesses); every object
 *       is asserted only once, so reference cycles and shared references end the recursion.</li>
 *   <li>Collections get a {@code contains(...)} or an {@code extracting(...)} assertion with
 *       method references and {@code Tuple.tuple(...)} values; {@code hasSize} only when the
 *       elements cannot be compared.</li>
 *   <li>An object whose expression is used more than once is stored in a {@code var} first.</li>
 *   <li>Getters that throw are skipped and documented by a one-line comment.</li>
 *   <li>Only public getters, record components and public fields are used. Members that belong
 *       to persistence proxies or bytecode enhancement (Hibernate, ByteBuddy, ...) are ignored.</li>
 *   <li>The generated code uses simple type names; missing imports are added directly below the
 *       package declaration of the test source.</li>
 * </ul>
 *
 * <p>The argument must be a variable or a field path without side effects. Getters must be
 * safe to invoke. Test sources must be UTF-8 and compiled with source file information.</p>
 *
 * <p>Set {@code -Dsmarttestutils.projectRoot=/path/to/project} to override automatic
 * discovery of {@code src/test/java} in the Maven module tree.</p>
 */
public final class SmartTestUtils {
    private static final int MAX_DEPTH = 3;
    private static final String ASSERTIONS = "org.assertj.core.api.Assertions";
    private static final String TUPLE = "org.assertj.core.groups.Tuple";
    private static final String IDENTIFIER = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
    // Only "SmartTestUtils.createAsserts(...)" is replaced, independent of the package this class was
    // copied to; the test imports SmartTestUtils from its local package (no static import).
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?m)^([ \\t]*)SmartTestUtils\\.createAsserts\\h*\\(\\h*("
                    + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*)\\h*\\)\\h*;(?=\\h*(?://.*)?$)");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\h*package\\h+[^;]+;\\h*(?:\\r?\\n)?");
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\h*import\\h+(static\\h+)?([\\w.$]+(?:\\.\\*)?)\\h*;");
    private static final Pattern DECLARATION = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\h+(" + IDENTIFIER + ")");
    /** Packages whose types are never inspected as beans (JDK, APIs, persistence and proxy frameworks). */
    private static final List<String> FOREIGN_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "jakarta.", "org.hibernate.", "net.bytebuddy.", "javassist.", "org.springframework.",
            "org.eclipse.persistence.", "org.apache.openjpa.", "org.assertj.", "org.mockito.");

    private SmartTestUtils() {
    }

    /**
     * Captures {@code object} and rewrites the calling test's assertion placeholder.
     * On a lookup, getter or generation failure the source file is left unchanged.
     *
     * @throws IllegalArgumentException if an argument or value is unsupported
     * @throws IllegalStateException if the source cannot be safely located or updated
     */
    public static synchronized void createAsserts(Object object) {
        StackWalker.StackFrame caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.filter(frame -> frame.getDeclaringClass() != SmartTestUtils.class)
                        .findFirst().orElseThrow(() -> new IllegalStateException("No calling test found")));
        if (caller.getFileName() == null) {
            throw new IllegalStateException("Compile the calling test with source file information");
        }
        try {
            Path source = findSource(caller);
            String current = Files.readString(source);
            var match = PLACEHOLDER.matcher(current);
            if (!match.find()) {
                throw new IllegalStateException("Expected a standalone createAsserts(variable); line in " + source);
            }
            var placeholder = match.toMatchResult();
            if (match.find()) {
                throw new IllegalStateException("Expected only one createAsserts placeholder in " + source);
            }
            String indent = placeholder.group(1);
            String unit = indent.contains("\t") ? "\t" : "    ";
            Imports imports = new Imports(current, caller.getDeclaringClass());
            Generator generator = new Generator(imports, unit);
            generator.root(object, placeholder.group(2));
            List<List<String>> groups = generator.groups();
            List<String> lines = new ArrayList<>();
            for (List<String> group : groups) {
                if (!lines.isEmpty()) {
                    lines.add("");
                }
                for (String statement : group) {
                    lines.addAll(Arrays.asList(statement.split("\n", -1)));
                }
            }
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("No supported values found in "
                        + placeholder.group(2));
            }
            String newline = current.contains("\r\n") ? "\r\n" : "\n";
            String replacement;
            if (groups.size() == 1 && groups.get(0).size() == 1) {
                replacement = String.join(newline + indent, lines);
            } else {
                // A block keeps multiple assertions together even inside an unbraced if or loop.
                StringBuilder block = new StringBuilder("{");
                for (String line : lines) {
                    block.append(newline);
                    if (!line.isEmpty()) {
                        block.append(indent).append(unit).append(line);
                    }
                }
                replacement = block.append(newline).append(indent).append('}').toString();
            }
            String updated = current.substring(0, placeholder.start()) + indent + replacement
                    + current.substring(placeholder.end());
            replaceSource(source, current, imports.apply(updated, newline));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read or update the calling test source", e);
        }
    }

    private static Path findSource(StackWalker.StackFrame caller) throws IOException {
        String className = caller.getClassName();
        int lastDot = className.lastIndexOf('.');
        String relative = (lastDot < 0 ? "" : className.substring(0, lastDot).replace('.', '/') + "/")
                + caller.getFileName();
        String override = System.getProperty("smarttestutils.projectRoot");
        if (override != null && !override.isBlank()) {
            return searchProject(Path.of(override).toAbsolutePath().normalize(), relative);
        }
        Path fromClasses = fromTestClasses(caller.getDeclaringClass(), relative);
        if (fromClasses != null) {
            return fromClasses;
        }
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path parent = directory; parent != null; parent = parent.getParent()) {
            Path candidate = parent.resolve("src/test/java").resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            if (Files.isRegularFile(parent.resolve("pom.xml"))) {
                return searchProject(parent, relative);
            }
        }
        throw new IllegalStateException("Cannot locate src/test/java/" + relative
                + "; set smarttestutils.projectRoot to the Maven project root");
    }

    /**
     * Maven test classes identify their module even when user.dir is the reactor root. Other
     * class loaders (e.g. Quarkus) may provide no code source, a null location or a non-file URL;
     * any failure here falls back to the project discovery via user.dir.
     */
    private static Path fromTestClasses(Class<?> caller, String relative) {
        try {
            var protection = caller.getProtectionDomain();
            var codeSource = protection == null ? null : protection.getCodeSource();
            var location = codeSource == null ? null : codeSource.getLocation();
            if (location == null) {
                return null;
            }
            Path classes = Path.of(location.toURI());
            if (classes.endsWith(Path.of("target", "test-classes"))) {
                Path candidate = classes.getParent().getParent().resolve("src/test/java").resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception | LinkageError ignored) {
            // Fall back to Maven project discovery.
        }
        return null;
    }

    private static Path searchProject(Path root, String relative) throws IOException {
        Path direct = root.resolve("src/test/java").resolve(relative);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        List<Path> matches = new ArrayList<>();
        // Do not traverse build output, Git data, or symbolic links into other trees.
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                return !dir.equals(root) && (name.equals("target") || name.startsWith("."))
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.endsWith(Path.of("src/test/java").resolve(relative))) {
                    matches.add(file);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected one Maven test source for " + relative
                    + " under " + root + "; found " + matches.size());
        }
        return matches.get(0);
    }

    /**
     * Collects the assertions as a tree of nodes, one node per asserted object or collection.
     * Each node is rendered as its own group; a node whose expression is used more than once
     * is stored in a local variable first.
     */
    private static final class Generator {
        private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte",
                "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum",
                "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
                "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
                "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
                "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "record", "yield",
                "sealed", "permits", "non", "_");

        private final Imports imports;
        private final String continuation;
        private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        private final Set<String> variables = new HashSet<>();
        private Node root;

        /** An asserted object or collection; {@code access} is appended to the parent's expression. */
        private static final class Node {
            final String access;
            final String name;
            final List<Function<String, String>> entries = new ArrayList<>();
            final List<Node> children = new ArrayList<>();
            int statements;

            Node(String access, String name) {
                this.access = access;
                this.name = name;
            }

            void statement(Function<String, String> statement) {
                entries.add(statement);
                statements++;
            }

            void comment(Function<String, String> comment) {
                entries.add(comment);
            }

            /** How often the node's expression appears in the generated code. */
            int uses() {
                int uses = statements;
                for (Node child : children) {
                    int childUses = child.uses();
                    uses += childUses >= 2 ? 1 : childUses;
                }
                return uses;
            }
        }

        Generator(Imports imports, String unit) {
            this.imports = imports;
            this.continuation = unit + unit;
        }

        void root(Object value, String expression) {
            root = new Node(expression, null);
            if (value == null) {
                root.statement(base -> assertThat(base) + ".isNull();");
            } else if (simple(root, value, value.getClass(), "")) {
                return;
            } else if (value instanceof Collection<?> collection) {
                visited.put(collection, Boolean.TRUE);
                collection(root, collection, null);
            } else if (isBean(userClass(value.getClass()))) {
                visited.put(value, Boolean.TRUE);
                object(root, value, 0);
            } else {
                throw new IllegalArgumentException("Unsupported value type at " + expression + ": "
                        + value.getClass().getTypeName());
            }
        }

        /** Groups of rendered statements in output order; multi-line statements contain '\n'. */
        List<List<String>> groups() {
            List<List<String>> groups = new ArrayList<>();
            render(root, "", groups);
            groups.removeIf(List::isEmpty);
            return groups;
        }

        private void render(Node node, String parent, List<List<String>> groups) {
            String base = parent + node.access;
            List<String> group = new ArrayList<>();
            if (node != root && node.uses() >= 2) {
                String variable = variable(node.name);
                group.add("var " + variable + " = " + base + ";");
                base = variable;
            }
            for (Function<String, String> entry : node.entries) {
                group.add(entry.apply(base));
            }
            groups.add(group);
            for (Node child : node.children) {
                render(child, base, groups);
            }
        }

        /** A local variable name that does not clash with any identifier of the test source. */
        private String variable(String name) {
            String candidate = name;
            for (int i = 2; KEYWORDS.contains(candidate) || imports.identifiers.contains(candidate)
                    || !variables.add(candidate); i++) {
                candidate = name + i;
            }
            return candidate;
        }

        private String assertThat(String expression) {
            return imports.assertThat() + "(" + expression + ")";
        }

        private void object(Node node, Object value, int depth) {
            List<Runnable> nested = new ArrayList<>();
            for (Property property : properties(userClass(value.getClass()))) {
                String access = property.access("");
                Object child;
                try {
                    child = property.read(value);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    node.comment(base -> "// " + base + access + " ausgelassen (" + describe(e) + ")");
                    continue;
                }
                if (simple(node, child, property.type(), access)) {
                    continue;
                }
                if (child instanceof Collection<?> collection) {
                    nested.add(() -> {
                        if (visited.put(collection, Boolean.TRUE) == null) {
                            Node childNode = new Node(access, property.name());
                            node.children.add(childNode);
                            collection(childNode, collection, property.genericType());
                        }
                    });
                } else if (child != null && depth + 1 < MAX_DEPTH && isBean(userClass(child.getClass()))) {
                    nested.add(() -> {
                        if (visited.put(child, Boolean.TRUE) == null) {
                            Node childNode = new Node(access, property.name());
                            node.children.add(childNode);
                            object(childNode, child, depth + 1);
                        }
                    });
                }
            }
            // Nested objects follow the object's own values, each in its own group.
            nested.forEach(Runnable::run);
        }

        /** Adds an assertion if the value has a supported type; returns false for other values. */
        private boolean simple(Node node, Object value, Class<?> declared, String access) {
            Class<?> type = value == null ? declared : value.getClass();
            if (!isSimple(type)) {
                return false;
            }
            if (value == null) {
                node.statement(base -> assertThat(base + access) + ".isNull();");
            } else if (value instanceof Boolean flag) {
                node.statement(base -> assertThat(base + access) + (flag ? ".isTrue();" : ".isFalse();"));
            } else {
                String literal = literal(value);
                if (literal != null) {
                    node.statement(base -> assertThat(base + access) + ".isEqualTo(" + literal + ");");
                }
            }
            return true;
        }

        private void collection(Node node, Collection<?> collection, Type declared) {
            if (collection.isEmpty()) {
                node.statement(base -> assertThat(base) + ".isEmpty();");
                return;
            }
            List<Object> elements = new ArrayList<>(collection);
            String contents = contents(node, elements, elementType(declared, elements));
            if (contents == null) {
                // The elements cannot be compared, so at least their number is checked.
                node.statement(base -> assertThat(base) + ".hasSize(" + elements.size() + ");");
            } else {
                node.statement(base -> assertThat(base) + contents);
            }
        }

        /** The assertion suffix comparing the elements, or null if they cannot be compared. */
        private String contents(Node node, List<Object> elements, Class<?> element) {
            if (element == null) {
                return null;
            }
            if (isSimple(element)) {
                List<String> values = new ArrayList<>();
                for (Object value : elements) {
                    String literal = value == null ? "null" : literal(value);
                    if (literal == null) {
                        return null;
                    }
                    values.add(literal);
                }
                return ".contains(" + nullSafe(values, element) + ");";
            }
            if (!isBean(element) || elements.contains(null)) {
                return null;
            }
            List<Property> extracted = new ArrayList<>();
            List<List<String>> columns = new ArrayList<>();
            for (Property property : properties(element)) {
                if (!isSimple(property.type()) || (isNamedType(property.type())
                        && imports.reference(property.type()) == null)) {
                    continue;
                }
                List<String> column = new ArrayList<>();
                try {
                    for (Object item : elements) {
                        Object value = property.read(item);
                        String literal = value == null ? "null" : literal(value);
                        if (literal == null) {
                            column = null;
                            break;
                        }
                        column.add(literal);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    String reference = extractor(element.getSimpleName(), property);
                    node.comment(base -> "// " + reference + " ausgelassen (" + describe(e) + ")");
                    continue;
                }
                if (column != null) {
                    extracted.add(property);
                    columns.add(column);
                }
            }
            String elementName = extracted.isEmpty() ? null : imports.reference(element);
            if (elementName == null) {
                return null;
            }
            List<String> rows = new ArrayList<>();
            for (int row = 0; row < elements.size(); row++) {
                List<String> values = new ArrayList<>();
                for (List<String> column : columns) {
                    values.add(column.get(row));
                }
                rows.add(extracted.size() == 1 ? values.get(0)
                        : imports.reference(TUPLE) + ".tuple(" + String.join(", ", values) + ")");
            }
            List<String> extractors = new ArrayList<>();
            for (Property property : extracted) {
                extractors.add(extractor(elementName, property));
            }
            String contains = extracted.size() == 1
                    ? ".contains(" + nullSafe(rows, extracted.get(0).type()) + ");"
                    : ".contains(\n" + continuation + continuation
                            + String.join(",\n" + continuation + continuation, rows) + ");";
            return "\n" + continuation + ".extracting(" + String.join(", ", extractors) + ")\n"
                    + continuation + contains;
        }

        private static String extractor(String elementName, Property property) {
            return property.member() instanceof Method method ? elementName + "::" + method.getName()
                    : "(" + elementName + " element) -> element." + property.member().getName();
        }

        /** A single {@code null} vararg must be cast, otherwise it is passed as a null array. */
        private String nullSafe(List<String> values, Class<?> type) {
            if (values.size() == 1 && values.get(0).equals("null")) {
                String name = type == boolean.class || type == Boolean.class ? "Boolean"
                        : type == String.class ? "String" : imports.reference(type);
                return "(" + name + ") null";
            }
            return String.join(", ", values);
        }

        private Class<?> elementType(Type declared, List<Object> elements) {
            if (declared instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1) {
                Type argument = parameterized.getActualTypeArguments()[0];
                if (argument instanceof WildcardType wildcard) {
                    argument = wildcard.getUpperBounds()[0];
                }
                if (argument instanceof Class<?> type && type != Object.class) {
                    return type;
                }
            }
            Class<?> common = null;
            for (Object element : elements) {
                if (element == null) {
                    continue;
                }
                Class<?> type = element instanceof Enum<?> constant ? constant.getDeclaringClass()
                        : userClass(element.getClass());
                if (common == null) {
                    common = type;
                }
                while (!common.isAssignableFrom(type)) {
                    common = common.getSuperclass();
                }
            }
            return common == Object.class ? null : common;
        }

        private String literal(Object value) {
            if (value instanceof String text) {
                return quote(text, '"');
            }
            if (value instanceof Boolean) {
                return value.toString();
            }
            if (value instanceof Enum<?> constant) {
                String type = imports.reference(constant.getDeclaringClass());
                return type == null ? null : type + "." + constant.name();
            }
            if (value instanceof LocalDate || value instanceof LocalDateTime) {
                return imports.reference(value.getClass()) + ".parse(" + quote(value.toString(), '"') + ")";
            }
            return null;
        }

        /** A single-line description of a getter failure for the generated comment. */
        private static String describe(Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : failure;
            String text = cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            text = text.replaceAll("\\s+", " ").strip();
            return text.length() > 120 ? text.substring(0, 117) + "..." : text;
        }
    }

    private record Property(String name, Member member, Class<?> type, Type genericType) {
        Object read(Object target) throws ReflectiveOperationException {
            if (member instanceof Method getter) {
                if (!getter.canAccess(target) && !getter.trySetAccessible()) {
                    throw new IllegalAccessException("Getter is inaccessible");
                }
                return getter.invoke(target);
            }
            Field field = (Field) member;
            if (!field.canAccess(target) && !field.trySetAccessible()) {
                throw new IllegalAccessException("Field is inaccessible");
            }
            return field.get(target);
        }

        String access(String expression) {
            return expression + "." + member.getName() + (member instanceof Method ? "()" : "");
        }
    }

    /** String, boolean, enum, LocalDate and LocalDateTime values are asserted directly. */
    private static boolean isSimple(Class<?> type) {
        return type == String.class || type == boolean.class || type == Boolean.class || isNamedType(type);
    }

    /** Supported value types whose literal needs a type name (and possibly an import). */
    private static boolean isNamedType(Class<?> type) {
        return type == LocalDate.class || type == LocalDateTime.class
                || (type != null && type.isEnum())
                || (type != null && type.getSuperclass() != null && type.getSuperclass().isEnum());
    }

    /** True for user types whose properties may be inspected: no JDK, framework, proxy, collection or array type. */
    private static boolean isBean(Class<?> type) {
        return !type.isArray() && !type.isPrimitive() && !type.isEnum() && !Proxy.isProxyClass(type)
                && !isProxy(type) && !isForeign(type) && !Collection.class.isAssignableFrom(type)
                && !Map.class.isAssignableFrom(type);
    }

    private static boolean isForeign(Class<?> type) {
        String name = type.getName();
        return FOREIGN_PACKAGES.stream().anyMatch(name::startsWith);
    }

    /** Detects runtime generated subclasses such as Hibernate/ByteBuddy, Javassist or CGLIB proxies. */
    private static boolean isProxy(Class<?> type) {
        String name = type.getName();
        return type.isSynthetic() || name.contains("$HibernateProxy") || name.contains("$$")
                || name.contains("$ByteBuddy$") || name.contains("_$$_");
    }

    /** The class written by the developer, i.e. the first superclass that is not a generated proxy. */
    private static Class<?> userClass(Class<?> type) {
        Class<?> result = type;
        while (isProxy(result) && result.getSuperclass() != null && result.getSuperclass() != Object.class) {
            result = result.getSuperclass();
        }
        return result;
    }

    /** Public getters, record components and public fields, ordered by property name. */
    private static List<Property> properties(Class<?> type) {
        Map<String, Property> properties = new TreeMap<>();
        if (type.isRecord()) {
            for (var component : type.getRecordComponents()) {
                Method accessor = component.getAccessor();
                properties.put(component.getName(),
                        new Property(component.getName(), accessor, accessor.getReturnType(), accessor.getGenericReturnType()));
            }
            return List.copyOf(properties.values());
        }
        // Sort for deterministic output, preferring boolean isX over getX.
        Arrays.stream(type.getMethods()).sorted(Comparator.comparing(Method::getName)).forEach(method -> {
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                    || method.isSynthetic() || method.isBridge() || method.getParameterCount() != 0
                    || method.getReturnType() == void.class || method.getDeclaringClass() == Object.class
                    || hidden(method.getName(), method.getDeclaringClass())) {
                return;
            }
            String name = method.getName();
            String property = null;
            if (name.startsWith("get") && name.length() > 3) {
                property = decapitalize(name.substring(3));
            } else if (name.startsWith("is") && name.length() > 2 && method.getReturnType() == boolean.class) {
                property = decapitalize(name.substring(2));
            }
            if (property != null) {
                properties.put(property, new Property(property, method, method.getReturnType(), method.getGenericReturnType()));
            }
        });
        Arrays.stream(type.getFields()).filter(field -> Modifier.isPublic(field.getModifiers())
                && !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()
                && !hidden(field.getName(), field.getDeclaringClass()))
                .sorted(Comparator.comparing(Field::getName))
                .forEach(field -> properties.putIfAbsent(field.getName(),
                        new Property(field.getName(), field, field.getType(), field.getGenericType())));
        return List.copyOf(properties.values());
    }

    /** Members injected by proxies or bytecode enhancement, e.g. {@code $$_hibernate_*} or HibernateProxy methods. */
    private static boolean hidden(String name, Class<?> declaringClass) {
        return name.contains("$") || isForeign(declaringClass) || isProxy(declaringClass);
    }

    private static String decapitalize(String name) {
        return name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))
                ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** Resolves type names without package prefixes and records the imports the test source still needs. */
    private static final class Imports {
        private final String packageName;
        private final String topLevel;
        private final Map<String, String> existing = new HashMap<>();
        private final Set<String> existingStatic = new HashSet<>();
        private final Set<String> declared = new HashSet<>();
        /** Every identifier of the test source; generated variables must not shadow or clash with them. */
        private final Set<String> identifiers = new HashSet<>();
        private final Map<String, String> added = new TreeMap<>();
        private final Set<String> addedStatic = new TreeSet<>();
        private String assertThat;

        Imports(String source, Class<?> caller) {
            Class<?> outer = caller;
            while (outer.getEnclosingClass() != null) {
                outer = outer.getEnclosingClass();
            }
            packageName = outer.getPackageName();
            topLevel = outer.getName();
            Matcher imports = IMPORT.matcher(source);
            while (imports.find()) {
                String name = imports.group(2);
                if (imports.group(1) != null) {
                    existingStatic.add(name);
                } else if (!name.endsWith(".*")) {
                    existing.put(simpleName(name), name);
                }
            }
            Matcher declarations = DECLARATION.matcher(source);
            while (declarations.find()) {
                declared.add(declarations.group(1));
            }
            Matcher words = Pattern.compile(IDENTIFIER).matcher(source);
            while (words.find()) {
                identifiers.add(words.group());
            }
        }

        String assertThat() {
            if (assertThat == null) {
                if (existingStatic.contains(ASSERTIONS + ".assertThat") || existingStatic.contains(ASSERTIONS + ".*")) {
                    assertThat = "assertThat";
                } else if (existingStatic.stream().noneMatch(name -> name.endsWith(".assertThat"))) {
                    addedStatic.add(ASSERTIONS + ".assertThat");
                    assertThat = "assertThat";
                } else {
                    // Another assertThat (e.g. Hamcrest) is statically imported: qualify with the class name.
                    assertThat = reference(ASSERTIONS) + ".assertThat";
                }
            }
            return assertThat;
        }

        /** The name to use for {@code type} in the test source, or null if the test cannot access it. */
        String reference(Class<?> type) {
            String canonical = type.getCanonicalName();
            if (canonical == null || !accessible(type)) {
                return null;
            }
            return reference(canonical, type.getPackageName(), type.getEnclosingClass() == null);
        }

        String reference(String canonical) {
            return reference(canonical, canonical.substring(0, canonical.lastIndexOf('.')), true);
        }

        private String reference(String canonical, String typePackage, boolean isTopLevel) {
            String outer = topLevel.replace('$', '.');
            if (canonical.equals(outer)) {
                return simpleName(canonical);
            }
            if (canonical.startsWith(outer + ".")) {
                // Member types of the test class are in scope without an import.
                return canonical.substring(outer.length() + 1);
            }
            String simple = simpleName(canonical);
            if (canonical.equals(existing.get(simple)) || canonical.equals(added.get(simple))) {
                return simple;
            }
            if (existing.containsKey(simple) || added.containsKey(simple) || declared.contains(simple)
                    || simple.equals(simpleName(outer))) {
                // The simple name is already taken; only the qualified name is unambiguous.
                return canonical;
            }
            if (isTopLevel && (typePackage.equals(packageName) || typePackage.equals("java.lang"))) {
                return simple;
            }
            added.put(simple, canonical);
            return simple;
        }

        private boolean accessible(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
                int modifiers = current.getModifiers();
                if (!Modifier.isPublic(modifiers)
                        && (Modifier.isPrivate(modifiers) || !current.getPackageName().equals(packageName))) {
                    return false;
                }
            }
            return true;
        }

        /** Inserts the missing imports directly below the package declaration. */
        String apply(String source, String newline) {
            List<String> lines = new ArrayList<>();
            addedStatic.forEach(name -> lines.add("import static " + name + ";"));
            added.values().stream().sorted().forEach(name -> lines.add("import " + name + ";"));
            if (lines.isEmpty()) {
                return source;
            }
            int position = 0;
            String prefix = "";
            Matcher packageDeclaration = PACKAGE.matcher(source);
            if (packageDeclaration.find()) {
                position = packageDeclaration.end();
                String separator = source.startsWith("\n", position - 1) ? "" : newline;
                if (source.startsWith(newline, position)) {
                    position += newline.length();
                    prefix = separator;
                } else {
                    prefix = separator + newline;
                }
            }
            String rest = source.substring(position);
            String block = prefix + String.join(newline, lines) + newline;
            if (!rest.stripLeading().startsWith("import ")) {
                block += newline;
            }
            return source.substring(0, position) + block + rest;
        }

        private static String simpleName(String name) {
            return name.substring(name.lastIndexOf('.') + 1);
        }
    }

    private static String quote(String text, char delimiter) {
        StringBuilder result = new StringBuilder().append(delimiter);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == delimiter) {
                result.append('\\').append(c);
            } else {
                switch (c) {
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    case '\b' -> result.append("\\b");
                    case '\f' -> result.append("\\f");
                    default -> {
                        if (c < 32 || c == 127) result.append(String.format("\\%03o", (int) c));
                        else if (c > 126) result.append(String.format("\\u%04x", (int) c));
                        else result.append(c);
                    }
                }
            }
        }
        return result.append(delimiter).toString();
    }

    private static void replaceSource(Path source, String original, String updated) throws IOException {
        Path temporary = Files.createTempFile(source.getParent(), ".smarttestutils-", ".java.tmp");
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8);
            if (Files.getFileStore(source).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(source));
            }
            if (!Files.readString(source, StandardCharsets.UTF_8).equals(original)) {
                throw new IllegalStateException("Test source changed during capture; no assertions were written");
            }
            try {
                Files.move(temporary, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IllegalStateException("Filesystem does not support atomic source replacement", e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
