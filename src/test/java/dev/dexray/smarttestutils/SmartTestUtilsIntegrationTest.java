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
                        public boolean isVisible() { return false; }
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
                .startsWith("package generated;\n\nimport static org.assertj.core.api.Assertions.assertThat;\n"
                        + "import dev.dexray.smarttestutils.SmartTestUtils;\n")
                .doesNotContain("SmartTestUtils.createAsserts(bean);")
                .contains("""
                                {
                                    assertThat(bean.getAbsent()).isNull();
                                    assertThat(bean.isEnabled()).isTrue();
                                    assertThat(bean.getEscaped()).isEqualTo("Zeile\\n\\"Zitat\\"\\\\Pfad");
                                    assertThat(bean.getName()).isEqualTo("TestName");
                                    assertThat(bean.getState()).isEqualTo(State.READY);

                                    var deep = bean.getChild().getDeep();
                                    assertThat(deep.getLabel()).isEqualTo("Depth3");
                                    assertThat(deep.isVisible()).isFalse();
                                }
                        """);
        assertThat(generatedBlock(rewritten)).doesNotContain("org.", "generated.", "java.")
                .doesNotContain("getIntValue", "getCharValue", "getNaN", "getBoxedValue", "getNegativeZero")
                .doesNotContain("getFurther()", "bean.getShared()", "bean.getSelf()", " == ");

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
    void addsMissingAssertJImportBelowPackageDeclaration() throws Throwable {
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
        assertThat(Files.readString(source)).isEqualTo("""
                package generated;

                import static org.assertj.core.api.Assertions.assertThat;
                import dev.dexray.smarttestutils.SmartTestUtils;

                public class Fixture {
                    public static void run() {
                        String value = "Snapshot";
                        assertThat(value).isEqualTo("Snapshot");
                    }
                }
                """);
    }

    @Test
    void reusesExistingImportsAndQualifiesOnlyOnConflicts() throws Throwable {
        writeSource("other/State.java", """
                package other;
                public enum State { ACTIVE }
                """);
        Path source = writeFixture("""
                package generated;
                import static org.assertj.core.api.Assertions.*;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public enum State { READY }
                    public static class Bean {
                        public State getLocal() { return State.READY; }
                        public other.State getForeign() { return other.State.ACTIVE; }
                    }
                    public static void run() {
                        Bean bean = new Bean();
                        SmartTestUtils.createAsserts(bean);
                    }
                }
                """);
        rewriteAndVerify(source, project.resolve("src/test/java/other/State.java"));
        String rewritten = Files.readString(source);
        assertThat(rewritten)
                .startsWith("package generated;\nimport static org.assertj.core.api.Assertions.*;")
                .contains("assertThat(bean.getLocal()).isEqualTo(State.READY);",
                        "assertThat(bean.getForeign()).isEqualTo(other.State.ACTIVE);")
                .doesNotContain("import other.State;", "import static org.assertj.core.api.Assertions.assertThat;");
    }

    @Test
    void generatesCollectionAssertionsWithExtractingAndTuples() throws Throwable {
        writeSource("model/Status.java", """
                package model;
                public enum Status { OPEN, CLOSED }
                """);
        writeSource("model/Item.java", """
                package model;
                public class Item {
                    private final String name;
                    private final Status status;
                    private final boolean active;
                    public Item(String name, Status status, boolean active) {
                        this.name = name;
                        this.status = status;
                        this.active = active;
                    }
                    public String getName() { return name; }
                    public Status getStatus() { return status; }
                    public boolean isActive() { return active; }
                    public String getCode() {
                        if (name == null) throw new IllegalStateException("kein Code");
                        return name;
                    }
                    public int getPriority() { return 7; }
                    protected String getInternal() { return "hidden"; }
                }
                """);
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                import java.util.List;
                import java.util.Set;
                import model.Item;
                import model.Status;
                public class Fixture {
                    public static boolean changed;
                    public static class Customer {
                        public String getName() { return "Kunde"; }
                        public boolean isVip() { return true; }
                        public java.time.LocalDateTime getLastLogin() {
                            return java.time.LocalDateTime.of(2024, 2, 29, 8, 15, 30);
                        }
                        public List<String> getAddresses() { return List.of("Hauptstr. 1"); }
                    }
                    public static class Order {
                        private final List<Item> items = List.of(new Item("Apfel", Status.OPEN, true),
                                new Item(changed ? "Birne" : null, Status.CLOSED, false));
                        private final Customer buyer = new Customer();
                        public String getNumber() { return "A-1"; }
                        public java.time.LocalDate getCreated() { return java.time.LocalDate.of(2024, 2, 29); }
                        public Customer getCustomer() { return buyer; }
                        public List<Item> getItems() { return items; }
                        public List<Item> getSameItems() { return items; }
                        public List<Status> getHistory() { return List.of(Status.OPEN, Status.CLOSED); }
                        public Set<String> getTags() { return Set.of("eilig"); }
                        public List<Label> getLabels() { return List.of(new Label("rot")); }
                        public List<Item> getNone() { return List.of(); }
                        public List<Integer> getNumbers() { return List.of(1, 2); }
                    }
                    public record Label(String text) {}
                    public static void run() {
                        Order order = new Order();
                        SmartTestUtils.createAsserts(order);
                    }
                }
                """);
        Path item = project.resolve("src/test/java/model/Item.java");
        Path status = project.resolve("src/test/java/model/Status.java");
        compile(source, item, status);
        try (URLClassLoader loader = fixtureLoader()) {
            invoke(loader, "run");
        }
        String rewritten = Files.readString(source);
        assertThat(rewritten)
                .startsWith("""
                        package generated;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import java.time.LocalDate;
                        import java.time.LocalDateTime;
                        import org.assertj.core.groups.Tuple;
                        import dev.dexray.smarttestutils.SmartTestUtils;
                        """)
                .contains("""
                                {
                                    assertThat(order.getCreated()).isEqualTo(LocalDate.parse("2024-02-29"));
                                    assertThat(order.getNumber()).isEqualTo("A-1");

                                    var customer = order.getCustomer();
                                    assertThat(customer.getLastLogin()).isEqualTo(LocalDateTime.parse("2024-02-29T08:15:30"));
                                    assertThat(customer.getName()).isEqualTo("Kunde");
                                    assertThat(customer.isVip()).isTrue();

                                    assertThat(customer.getAddresses()).contains("Hauptstr. 1");

                                    assertThat(order.getHistory()).contains(Status.OPEN, Status.CLOSED);

                                    // Item::getCode ausgelassen (IllegalStateException: kein Code)
                                    assertThat(order.getItems())
                                            .extracting(Item::isActive, Item::getName, Item::getStatus)
                                            .contains(
                                                    Tuple.tuple(true, "Apfel", Status.OPEN),
                                                    Tuple.tuple(false, null, Status.CLOSED));

                                    assertThat(order.getLabels())
                                            .extracting(Label::text)
                                            .contains("rot");

                                    assertThat(order.getNone()).isEmpty();

                                    assertThat(order.getNumbers()).hasSize(2);

                                    assertThat(order.getTags()).contains("eilig");
                                }
                        """);
        assertThat(generatedBlock(rewritten)).doesNotContain("org.", "model.", "generated.", "java.")
                .doesNotContain("getSameItems", "getPriority", "getInternal", "hasSize(1)");

        compile(source, item, status);
        try (URLClassLoader loader = fixtureLoader()) {
            invoke(loader, "run");
            loader.loadClass("generated.Fixture").getField("changed").setBoolean(null, true);
            assertThatThrownBy(() -> invoke(loader, "run")).isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void avoidsVariableNamesThatAlreadyExistInTheTest() throws Throwable {
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static class Address {
                        public String getCity() { return "Berlin"; }
                        public String getStreet() { return "Hauptstr."; }
                    }
                    public static class Person {
                        public Address getAddress() { return new Address(); }
                    }
                    public static void run() {
                        String address = "local";
                        Person person = new Person();
                        SmartTestUtils.createAsserts(person);
                    }
                }
                """);
        rewriteAndVerify(source);
        assertThat(generatedBlock(Files.readString(source)))
                .contains("var address2 = person.getAddress();", "assertThat(address2.getCity())");
    }

    @Test
    void ignoresMembersOfPersistenceProxiesAndNonPublicProperties() throws Throwable {
        writeSource("org/hibernate/proxy/HibernateProxy.java", """
                package org.hibernate.proxy;
                public interface HibernateProxy {
                    Object getHibernateLazyInitializer();
                    boolean isSession();
                }
                """);
        writeSource("org/hibernate/collection/PersistentBag.java", """
                package org.hibernate.collection;
                public class PersistentBag<E> extends java.util.ArrayList<E> {
                    public PersistentBag(java.util.Collection<E> values) { super(values); }
                    public boolean isDirty() { return true; }
                    public Object getSession() { throw new IllegalStateException("no session"); }
                }
                """);
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static class Customer {
                        public String getName() { return "Kunde"; }
                        public java.util.List<String> getPhones() {
                            return new org.hibernate.collection.PersistentBag<>(java.util.List.of("0123"));
                        }
                        protected String getSecret() { return "protected"; }
                        String getPackageVisible() { return "package"; }
                        public String $$_hibernate_getEntityEntry() { return "enhanced"; }
                    }
                    public static class Customer$HibernateProxy$abc extends Customer
                            implements org.hibernate.proxy.HibernateProxy {
                        public Object getHibernateLazyInitializer() { throw new IllegalStateException("proxy"); }
                        public boolean isSession() { throw new IllegalStateException("isSession"); }
                        public String getProxyOnly() { throw new IllegalStateException("proxy only"); }
                    }
                    public static void run() {
                        Customer customer = new Customer$HibernateProxy$abc();
                        SmartTestUtils.createAsserts(customer);
                    }
                }
                """);
        Path proxy = project.resolve("src/test/java/org/hibernate/proxy/HibernateProxy.java");
        Path bag = project.resolve("src/test/java/org/hibernate/collection/PersistentBag.java");
        rewriteAndVerify(source, proxy, bag);
        assertThat(generatedBlock(Files.readString(source)))
                .contains("assertThat(customer.getName()).isEqualTo(\"Kunde\");",
                        "assertThat(customer.getPhones()).contains(\"0123\");")
                .doesNotContain("Session", "LazyInitializer", "ProxyOnly", "Secret", "PackageVisible", "hibernate_",
                        "isDirty", "hasSize", "ausgelassen");
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
    void supportsRecordsFieldsInheritedBooleansAndIgnoresOtherValueTypesWithTabs() throws Throwable {
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
        assertThat(generatedBlock(Files.readString(source)))
                .contains("assertThat(bean.title).isEqualTo(\"public field\");",
                        "assertThat(bean.getDetail().text()).isEqualTo(",
                        "assertThat(bean.isReady()).isTrue();",
                        "assertThat(bean.getKind()).isEqualTo(Kind.SPECIAL);",
                        "assertThat(bean.getDate()).isEqualTo(LocalDate.parse(\"2024-02-29\"));",
                        "assertThat(bean.getDateTime()).isEqualTo(LocalDateTime.parse(\"2024-02-29T12:34\"));")
                .doesNotContain("bean.getReady()", "getDecimal", "getTime()", "getId()", "getZone", "getUnicode",
                        "number()", "BigDecimal.", "java.time.LocalDate.parse");
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
    void skipsFailingGettersWithAComment() throws Throwable {
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
                        public String getFailure() { throw new IllegalStateException("getter\\nfailed"); }
                    }
                }
                """);
        rewriteAndVerify(source);
        assertThat(Files.readString(source)).contains("""
                        {
                            assertThat(bean.getBeforeFailure()).isEqualTo("okay");
                            // bean.getFailure() ausgelassen (IllegalStateException: getter failed)
                        }
                """);
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

    @Test
    void fallsBackToWorkingDirectoryWhenCodeSourceLocationIsNull() throws Throwable {
        Path source = writeFixture("""
                package generated;
                import dev.dexray.smarttestutils.SmartTestUtils;
                public class Fixture {
                    public static void run() {
                        String value = "quarkus";
                        SmartTestUtils.createAsserts(value);
                    }
                }
                """);
        compile(source);
        byte[] bytes = Files.readAllBytes(project.resolve("target/test-classes/generated/Fixture.class"));
        // Like Quarkus, define the class without a code source location.
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals("generated.Fixture")) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        assertThat(loader.loadClass("generated.Fixture").getProtectionDomain().getCodeSource().getLocation()).isNull();
        String previous = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", project.toString());
            invoke(loader, "run");
        } finally {
            System.setProperty("user.dir", previous);
        }
        assertThat(Files.readString(source)).contains("assertThat(value).isEqualTo(\"quarkus\");");
    }

    private static void restoreProjectRoot(String previous) {
        if (previous == null) {
            System.clearProperty("smarttestutils.projectRoot");
        } else {
            System.setProperty("smarttestutils.projectRoot", previous);
        }
    }

    private Path writeFixture(String contents) throws IOException {
        return writeSource("generated/Fixture.java", contents);
    }

    private Path writeSource(String relative, String contents) throws IOException {
        Path source = project.resolve("src/test/java").resolve(relative);
        Files.createDirectories(source.getParent());
        Files.writeString(source, contents, StandardCharsets.UTF_8);
        return source;
    }

    /** The text between the first generated statement and the end of its block. */
    private static String generatedBlock(String source) {
        int start = source.indexOf("assertThat(");
        int variable = source.indexOf(" var ");
        if (variable >= 0 && variable < start) {
            start = variable + 1;
        }
        return source.substring(start, source.indexOf('}', start));
    }

    private void rewriteAndVerify(Path... sources) throws Throwable {
        compile(sources);
        try (URLClassLoader loader = fixtureLoader()) {
            invoke(loader, "run");
        }
        assertThat(Files.readString(sources[0])).doesNotContain("SmartTestUtils.createAsserts(");
        compile(sources);
        try (URLClassLoader loader = fixtureLoader()) {
            invoke(loader, "run");
        }
    }

    private void compile(Path... sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Integration tests require a JDK").isNotNull();
        Path classes = project.resolve("target/test-classes");
        Files.createDirectories(classes);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean successful = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "17", "-g", "-encoding", "UTF-8", "-classpath",
                            System.getProperty("java.class.path"), "-d", classes.toString()),
                    null, files.getJavaFileObjects(sources)).call();
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
