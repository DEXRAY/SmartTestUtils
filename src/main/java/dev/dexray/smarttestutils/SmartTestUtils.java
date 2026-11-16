package dev.dexray.smarttestutils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Replaces a standalone {@code SmartTestUtils.createAsserts(object);} in a Maven test
 * source with AssertJ assertions for the object's current state. The root has depth 0;
 * at most three property accesses are followed. Recompile and rerun to execute the generated assertions.
 *
 * <p>The argument must be a variable or a field path without side effects. Public
 * JavaBean getters, record components and public instance fields are supported.
 * Getters must be safe to invoke, and accessible through the argument's declared type.
 * Test sources must be UTF-8 and compiled with source file information.</p>
 *
 * <p>Set {@code -Dsmarttestutils.projectRoot=/path/to/project} to override automatic
 * discovery of {@code src/test/java} in the Maven module tree.</p>
 */
public final class SmartTestUtils {
    private static final int MAX_DEPTH = 3;
    private static final String ASSERT = "org.assertj.core.api.Assertions.assertThat(";
    private static final String IDENTIFIER = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?m)^([ \\t]*)(?:(?:dev\\.dexray\\.smarttestutils\\.)?SmartTestUtils\\.)?createAsserts\\h*\\(\\h*("
                    + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*)\\h*\\)\\h*;(?=\\h*(?://.*)?$)");

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
            List<String> assertions = new ArrayList<>();
            capture(object, placeholder.group(2), 0, new IdentityHashMap<>(), assertions);
            String indent = placeholder.group(1);
            String newline = current.contains("\r\n") ? "\r\n" : "\n";
            // A block keeps multiple assertions together even inside an unbraced if or loop.
            String replacement = assertions.size() == 1 ? assertions.get(0)
                    : "{" + newline + indent + "    " + String.join(newline + indent + "    ", assertions)
                    + newline + indent + "}";
            replaceSource(source, current, current.substring(0, placeholder.start()) + indent + replacement
                    + current.substring(placeholder.end()));
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
        // Maven test classes identify their module even when user.dir is the reactor root.
        var protection = caller.getDeclaringClass().getProtectionDomain();
        if (protection != null && protection.getCodeSource() != null) {
            try {
                Path classes = Path.of(protection.getCodeSource().getLocation().toURI());
                if (classes.endsWith(Path.of("target", "test-classes"))) {
                    Path candidate = classes.getParent().getParent().resolve("src/test/java").resolve(relative);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            } catch (java.net.URISyntaxException | IllegalArgumentException ignored) {
                // Fall back to Maven project discovery for other class loaders.
            }
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

    private static void capture(Object value, String expression, int depth,
            IdentityHashMap<Object, String> visited, List<String> assertions) {
        if (value == null) {
            assertions.add(ASSERT + expression + ").isEqualTo((Object) null);");
            return;
        }
        String literal = literal(value);
        if (literal != null) {
            assertions.add(ASSERT + expression + ").isEqualTo(" + literal + ");");
            return;
        }
        String first = visited.get(value);
        if (first != null) {
            assertions.add(ASSERT + expression + " == " + first + ").isEqualTo(true);");
            return;
        }
        visited.put(value, expression);
        assertions.add(ASSERT + expression + " != null).isEqualTo(true);");
        if (depth == MAX_DEPTH) {
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray() || type.getPackageName().startsWith("java.")
                || type.getPackageName().startsWith("javax.")) {
            throw new IllegalArgumentException("Unsupported value type at " + expression + ": "
                    + type.getTypeName() + " (collections and arrays require explicit assertions)");
        }
        for (var property : properties(type).entrySet()) {
            Object member = property.getValue();
            String access;
            Object child;
            try {
                if (member instanceof Method getter) {
                    if (!getter.canAccess(value) && !getter.trySetAccessible()) {
                        throw new IllegalAccessException("Getter is inaccessible");
                    }
                    child = getter.invoke(value);
                    access = expression + "." + getter.getName() + "()";
                } else {
                    Field field = (Field) member;
                    if (!field.canAccess(value) && !field.trySetAccessible()) {
                        throw new IllegalAccessException("Field is inaccessible");
                    }
                    child = field.get(value);
                    access = expression + "." + field.getName();
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Cannot read property " + expression + "." + property.getKey(), e);
            }
            capture(child, access, depth + 1, visited, assertions);
        }
    }

    private static Map<String, Object> properties(Class<?> type) {
        Map<String, Object> properties = new TreeMap<>();
        if (type.isRecord()) {
            for (var component : type.getRecordComponents()) {
                properties.put(component.getName(), component.getAccessor());
            }
            return properties;
        }
        // Sort for deterministic output, preferring boolean isX over getX.
        Arrays.stream(type.getMethods()).sorted(Comparator.comparing(Method::getName)).forEach(method -> {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge()
                    || method.getParameterCount() != 0 || method.getReturnType() == void.class
                    || method.getName().equals("getClass")) {
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
                properties.put(property, method);
            }
        });
        Arrays.stream(type.getFields()).filter(field -> !Modifier.isStatic(field.getModifiers())
                && !field.isSynthetic()).sorted(Comparator.comparing(Field::getName))
                .forEach(field -> properties.putIfAbsent(field.getName(), field));
        return properties;
    }

    private static String decapitalize(String name) {
        return name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))
                ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static String literal(Object value) {
        if (value instanceof String text) return quote(text, '"');
        if (value instanceof Character character) return quote(character.toString(), '\'');
        if (value instanceof Boolean || value instanceof Integer) return value.toString();
        if (value instanceof Byte) return "(byte) " + value;
        if (value instanceof Short) return "(short) " + value;
        if (value instanceof Long) return value + "L";
        if (value instanceof Float number) {
            if (number.isNaN()) return "java.lang.Float.valueOf(java.lang.Float.NaN)";
            if (number.isInfinite()) return "java.lang.Float." + (number > 0 ? "POSITIVE" : "NEGATIVE") + "_INFINITY";
            return number + "F";
        }
        if (value instanceof Double number) {
            if (number.isNaN()) return "java.lang.Double.valueOf(java.lang.Double.NaN)";
            if (number.isInfinite()) return "java.lang.Double." + (number > 0 ? "POSITIVE" : "NEGATIVE") + "_INFINITY";
            return number + "D";
        }
        if (value instanceof Enum<?> enumeration) {
            String type = enumeration.getDeclaringClass().getCanonicalName();
            if (type == null) throw new IllegalArgumentException("Enum has no source-accessible name");
            return type + "." + enumeration.name();
        }
        if (value instanceof BigDecimal) return "new java.math.BigDecimal(" + quote(value.toString(), '"') + ")";
        if (value instanceof BigInteger) return "new java.math.BigInteger(" + quote(value.toString(), '"') + ")";
        if (value instanceof UUID) return "java.util.UUID.fromString(" + quote(value.toString(), '"') + ")";
        if (value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime
                || value instanceof OffsetTime || value instanceof OffsetDateTime || value instanceof ZonedDateTime
                || value instanceof Instant || value instanceof Duration || value instanceof Period
                || value instanceof Year || value instanceof YearMonth || value instanceof MonthDay) {
            return value.getClass().getName() + ".parse(" + quote(value.toString(), '"') + ")";
        }
        if (value instanceof ZoneId zone) return "java.time.ZoneId.of(" + quote(zone.getId(), '"') + ")";
        return null;
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
