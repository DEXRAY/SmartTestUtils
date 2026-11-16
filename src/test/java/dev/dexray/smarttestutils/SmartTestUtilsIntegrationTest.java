package dev.dexray.smarttestutils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmartTestUtilsIntegrationTest {
    @TempDir
    Path project;

    @Test
    void rewritesActualCallerAndGeneratedAssertionsCompileAndDetectChanges() throws Throwable {
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static boolean changed;
                    public static void run() {
                        Bean bean = new Bean();
                        SmartTestUtils.createAsserts(bean);
                    }
                    public enum State { READY, DONE }
                    public static class Bean {
                        private final Nested child = new Nested();
                        public String getName() { return changed ? "Changed" : "TestName"; }
                        public String getEscaped() { return "Zeile\\n\\\"Zitat\\\"\\\\Pfad"; }
                        public boolean isEnabled() { return true; }
                        public byte getByteValue() { return (byte) 7; }
                        public short getShortValue() { return (short) 123; }
                        public int getIntValue() { return 12345; }
                        public long getLongValue() { return 9000000000L; }
                        public float getFloatValue() { return 1.25f; }
                        public double getDoubleValue() { return 1.125d; }
                        public char getCharValue() { return '\\''; }
                        public Integer getBoxedValue() { return 42; }
                        public Double getNaN() { return Double.NaN; }
                        public Double getInfinity() { return Double.POSITIVE_INFINITY; }
                        public Float getNegativeInfinity() { return Float.NEGATIVE_INFINITY; }
                        public double getNegativeZero() { return -0.0d; }
                        public String getAbsent() { return null; }
                        public State getState() { return State.READY; }
                        public char getCharacter() { return '\\''; }
                        public Nested getChild() { return child; }
                        public Nested getShared() { return child; }
                        public Bean getSelf() { return this; }
                    }
                    public static class Nested {
                        public Deep getDeep() { return new Deep(); }
                    }
                    public static class Deep {
                        public String getLabel() { return "Depth3"; }
                        public TooDeep getFurther() { return new TooDeep(); }
                    }
                    public static class TooDeep {
                        public String getExplodes() { throw new AssertionError("depth four visited"); }
                    }
                }
                """);

        compile(source);
        try (URLClassLoader loader = fixtureLoader()) {
            invoke(loader, "run");
        }

        String rewritten = Files.readString(source);
        assertThat(rewritten)
                .doesNotContain("SmartTestUtils.createAsserts(bean);")
                .contains("assertThat(")
                .contains("bean.getName()", "TestName", "bean.getChild().getDeep().getLabel()", "Depth3")
                .contains(" == bean).isEqualTo(true)", "isEqualTo((Object) null)")
                .doesNotContain(".isSameAs(", ".isNotNull(", ".isNull(")
                .doesNotContain("getFurther().getExplodes()", "bean.getShared().getDeep()", "bean.getSelf().getName()");

        compile(source);
        try (URLClassLoader loader = fixtureLoader()) {
            invoke(loader, "run");
            Class<?> fixture = loader.loadClass("generated.Fixture");
            fixture.getField("changed").setBoolean(null, true);
            assertThatThrownBy(() -> invoke(loader, "run")).isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void refusesMultiplePlaceholdersWithoutChangingSource() throws Exception {
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static void run() {
                        String value = "value";
                        SmartTestUtils.createAsserts(value);
                        SmartTestUtils.createAsserts(value);
                    }
                }
                """);
        assertRewriteRejectedWithoutChanges(source);
    }

    @Test
    void generatedAssertionsRunWithoutAnAssertJImport() throws Throwable {
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static void run() {
                        String value = "Snapshot";
                        SmartTestUtils.createAsserts(value);
                    }
                }
                """);
        rewriteAndVerify(source);
    }

    @Test
    void rewritesInvocationInsideLambdaBlock() throws Throwable {
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static void run() {
                        String value = "value";
                        Runnable action = () -> {
                            SmartTestUtils.createAsserts(value);
                        };
                        action.run();
                    }
                }
                """);
        rewriteAndVerify(source);
    }

    @Test
    void supportsRecordsFieldsInheritedBooleansAndStandardValueTypesWithTabs() throws Throwable {
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static void run() {
                        Bean bean = new Bean();
                        SmartTestUtils.createAsserts(bean);
                    }
                    public record Detail(String text, Integer number) {}
                    public static class Base {
                        public boolean isReady() { return true; }
                    }
                    public enum Kind {
                        SPECIAL { public String description() { return "special"; } };
                        public abstract String description();
                    }
                    public static class Bean extends Base {
                        public String title = "public field";
                        public boolean getReady() { return false; }
                        public Detail getDetail() { return new Detail("Grüße 😀", 17); }
                        public Kind getKind() { return Kind.SPECIAL; }
                        public String getControls() { return new String(new char[] {0, 1, 127, 0x2028, 0x2029}); }
                        public char getBackslash() { return '\\\\'; }
                        public char getUnicode() { return 'ä'; }
                        public java.math.BigDecimal getDecimal() { return new java.math.BigDecimal("123.4500"); }
                        public java.math.BigInteger getInteger() { return new java.math.BigInteger("999999999999999999999"); }
                        public java.util.UUID getId() { return java.util.UUID.fromString("12345678-1234-1234-1234-123456789abc"); }
                        public java.time.LocalDate getDate() { return java.time.LocalDate.of(2024, 2, 29); }
                        public java.time.LocalTime getTime() { return java.time.LocalTime.of(12, 34, 56, 123000000); }
                        public java.time.LocalDateTime getDateTime() { return java.time.LocalDateTime.of(2024, 2, 29, 12, 34); }
                        public java.time.OffsetTime getOffsetTime() { return java.time.OffsetTime.parse("12:34:56+02:00"); }
                        public java.time.OffsetDateTime getOffsetDateTime() { return java.time.OffsetDateTime.parse("2024-02-29T12:34:56+02:00"); }
                        public java.time.ZonedDateTime getZonedDateTime() { return java.time.ZonedDateTime.parse("2024-02-29T12:34:56+01:00[Europe/Berlin]"); }
                        public java.time.Instant getInstant() { return java.time.Instant.parse("2024-02-29T12:34:56Z"); }
                        public java.time.Duration getDuration() { return java.time.Duration.ofSeconds(123, 456); }
                        public java.time.Period getPeriod() { return java.time.Period.of(1, 2, 3); }
                        public java.time.Year getYear() { return java.time.Year.of(2024); }
                        public java.time.YearMonth getYearMonth() { return java.time.YearMonth.of(2024, 2); }
                        public java.time.MonthDay getMonthDay() { return java.time.MonthDay.of(2, 29); }
                        public java.time.ZoneId getZone() { return java.time.ZoneId.of("Europe/Berlin"); }
                    }
                }
                """.replace("    ", "\t"));
        rewriteAndVerify(source);
        assertThat(Files.readString(source)).contains("bean.title", "bean.getDetail().text()", "bean.isReady()")
                .doesNotContain("bean.getReady()");
    }

    @Test
    void refusesArgumentWithSideEffectsWithoutChangingSource() throws Exception {
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static String nextValue() { return "value"; }
                    public static void run() {
                        SmartTestUtils.createAsserts(nextValue());
                    }
                }
                """);
        assertRewriteRejectedWithoutChanges(source);
    }

    @Test
    void leavesOriginalSourceUntouchedWhenGetterFails() throws Exception {
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static void run() {
                        Bean bean = new Bean();
                        SmartTestUtils.createAsserts(bean);
                    }
                    public static class Bean {
                        public String getBeforeFailure() { return "okay"; }
                        public String getFailure() { throw new IllegalStateException("getter failed"); }
                    }
                }
                """);
        assertRewriteRejectedWithoutChanges(source);
    }

    @Test
    void preservesCrLfAndSurroundingComments() throws Throwable {
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static void run() {
                        String value = "Snapshot";
                        // keep the preceding comment
                        SmartTestUtils.createAsserts(value); // keep the trailing comment
                    }
                }
                """.replace("\n", "\r\n"));
        compile(source);
        try (URLClassLoader loader = fixtureLoader()) {
            invoke(loader, "run");
        }
        String rewritten = Files.readString(source);
        assertThat(rewritten)
                .contains("// keep the preceding comment\r\n")
                .contains("// keep the trailing comment\r\n");
        assertThat(rewritten.replace("\r\n", "")).doesNotContain("\n");
        compile(source);
        try (URLClassLoader loader = fixtureLoader()) {
            invoke(loader, "run");
        }
    }

    @Test
    void findsSourceInNestedMavenModuleUsingProjectRootOverride() throws Throwable {
        Path initial = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static void run() {
                        String value = "nested module";
                        SmartTestUtils.createAsserts(value);
                    }
                }
                """);
        Path source = project.resolve("modules/library/src/test/java/generated/Fixture.java");
        Files.createDirectories(source.getParent());
        Files.move(initial, source);
        String previous = System.getProperty("smarttestutils.projectRoot");
        try {
            System.setProperty("smarttestutils.projectRoot", project.toString());
            rewriteAndVerify(source);
            assertThat(Files.readString(source)).contains("assertThat(value).isEqualTo(\"nested module\")");
        } finally {
            restoreProjectRoot(previous);
        }
    }

    @Test
    void refusesAmbiguousModuleSourcesWithoutChangingEitherFile() throws Throwable {
        Path initial = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static void run() {
                        String value = "ambiguous";
                        SmartTestUtils.createAsserts(value);
                    }
                }
                """);
        Path first = project.resolve("first/src/test/java/generated/Fixture.java");
        Path second = project.resolve("second/src/test/java/generated/Fixture.java");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.move(initial, first);
        Files.copy(first, second);
        String before = Files.readString(first);
        compile(first);
        String previous = System.getProperty("smarttestutils.projectRoot");
        try {
            System.setProperty("smarttestutils.projectRoot", project.toString());
            try (URLClassLoader loader = fixtureLoader()) {
                assertThatThrownBy(() -> invoke(loader, "run"))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("found 2");
            }
        } finally {
            restoreProjectRoot(previous);
        }
        assertThat(Files.readString(first)).isEqualTo(before);
        assertThat(Files.readString(second)).isEqualTo(before);
    }

    private static void restoreProjectRoot(String previous) {
        if (previous == null) {
            System.clearProperty("smarttestutils.projectRoot");
        } else {
            System.setProperty("smarttestutils.projectRoot", previous);
        }
    }

    private Path writeFixture(String contents) throws IOException {
        Path source = project.resolve("src/test/java/generated/Fixture.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, contents, StandardCharsets.UTF_8);
        return source;
    }

    private void rewriteAndVerify(Path source) throws Throwable {
        compile(source);
        try (URLClassLoader loader = fixtureLoader()) {
            invoke(loader, "run");
        }
        assertThat(Files.readString(source)).doesNotContain("SmartTestUtils.createAsserts(");
        compile(source);
        try (URLClassLoader loader = fixtureLoader()) {
            invoke(loader, "run");
        }
    }

    private void compile(Path source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Integration tests require a JDK").isNotNull();
        Path classes = project.resolve("target/test-classes");
        Files.createDirectories(classes);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean successful = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "17", "-g", "-encoding", "UTF-8", "-classpath",
                            System.getProperty("java.class.path"), "-d", classes.toString()),
                    null, files.getJavaFileObjects(source.toFile())).call();
            assertThat(successful).as("Generated source must compile: %s", diagnostics.getDiagnostics()).isTrue();
        }
    }

    private URLClassLoader fixtureLoader() throws IOException {
        return new URLClassLoader(new java.net.URL[] {project.resolve("target/test-classes").toUri().toURL()},
                getClass().getClassLoader());
    }

    private void assertRewriteRejectedWithoutChanges(Path source) throws Exception {
        String before = Files.readString(source);
        compile(source);
        try (URLClassLoader loader = fixtureLoader()) {
            assertThatThrownBy(() -> invoke(loader, "run"))
                    .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
        }
        assertThat(Files.readString(source)).isEqualTo(before);
    }

    private static void invoke(ClassLoader loader, String method) throws Throwable {
        try {
            loader.loadClass("generated.Fixture").getMethod(method).invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
