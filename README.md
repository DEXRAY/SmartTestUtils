# SmartTestUtils

Eine kleine Java-Bibliothek, die den aktuellen Zustand eines Objekts als AssertJ-Assertions
direkt in den Quelltext eines Maven-Tests schreibt. Die Implementierung besteht aus einer
öffentlichen Klasse: `dev.dexray.smarttestutils.SmartTestUtils`.

## Voraussetzungen und Build

- JDK 17 oder neuer, Maven 3.9 oder neuer
- Testquellen unter `src/test/java`, mit UTF-8 und Quelldateinamen in den Debug-Informationen
- AssertJ im Test-Classpath des verwendenden Projekts

```sh
mvn clean verify
mvn install
```

Die Integrationstests kompilieren temporäre Testklassen, führen die Quelltext-Ersetzung aus,
kompilieren das Ergebnis erneut und prüfen auch, dass veränderte Testdaten zu einem
Assertion-Fehler führen. Dabei werden keine Testquellen dieses Repositorys überschrieben.

## Verwendung

Nach `mvn install` kann die Bibliothek in einem anderen Maven-Projekt eingebunden werden:

```xml
<dependency>
    <groupId>dev.dexray</groupId>
    <artifactId>smart-test-utils</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <version>3.27.3</version>
    <scope>test</scope>
</dependency>
```

Im Test genau eine Platzhalterzeile einfügen:

```java
import dev.dexray.smarttestutils.SmartTestUtils;

// innerhalb einer Testmethode:
Person object = new Person("TestName", 42);
SmartTestUtils.createAsserts(object);
```

Beim Ausführen wird ausschließlich die Platzhalter-Anweisung ersetzt, beispielsweise durch:

```java
{
    org.assertj.core.api.Assertions.assertThat(object != null).isEqualTo(true);
    org.assertj.core.api.Assertions.assertThat(object.getAge()).isEqualTo(42);
    org.assertj.core.api.Assertions.assertThat(object.getName()).isEqualTo("TestName");
}
```

**Den Test erneut kompilieren und ausführen**, etwa mit einem zweiten `mvn test`.
Der erste Lauf erfasst die Werte per Reflection. Der zweite Lauf prüft sie.
Die generierten Assertions verwenden voll qualifizierte Namen und benötigen daher keinen
zusätzlichen Import. Der Generator selbst verwendet ausschließlich das JDK.

## Unterstützte Properties und Werte

- Öffentliche JavaBean-Getter (`getName()`, `isEnabled()`), einschließlich geerbter Getter
- Record-Komponenten und öffentliche Instanzfelder; statische Member werden ausgelassen
- `String`, `char`, `boolean`, primitive Zahlentypen und ihre Wrapper, einschließlich `null`,
  `NaN` und Unendlichkeiten
- Enums, `BigDecimal`, `BigInteger`, `UUID`
- `java.time`: `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetTime`, `OffsetDateTime`,
  `ZonedDateTime`, `Instant`, `Duration`, `Period`, `Year`, `YearMonth`, `MonthDay`, `ZoneId`

Properties werden alphabetisch ausgegeben. Getter haben Vorrang vor gleichnamigen Feldern;
bei booleschen Properties hat `isX()` Vorrang vor `getX()`. `getClass()` wird ausgelassen.
Strings und Zeichen werden als escapte Java-Literale geschrieben, Enums mit qualifiziertem Typnamen.

Das Wurzelobjekt hat Tiefe 0. Es werden höchstens drei Property-Zugriffe verfolgt,
beispielsweise `object.getAddress().getCity().getName()`. Komplexe Objekte an der Tiefengrenze
werden nur auf ihren Nullzustand geprüft. Eine Identitätsprüfung erkennt Zyklen und gemeinsame
Referenzen: Wiederholte Referenzen werden mit `assertThat(a == b).isEqualTo(true)` geprüft, ihre Properties werden nicht erneut gelesen.

## Quelltext-Suche und Grenzen

Der Generator ermittelt die aufrufende Testdatei über den Stack und sucht den Platzhalter
per Volltextsuche. Schreibe den Aufruf einmal pro Datei als eigene, einzeilige Anweisung.
Das Argument muss eine Variable oder ein Feldpfad wie `this.object` sein. Der erste
Lauf darf den Platzhalter nur einmal ausführen. Kommentare und Textblöcke dürfen keine
identisch aussehende Platzhalterzeile enthalten.

Die Quelldatei wird im Maven-Modul unter `src/test/java` gesucht. Für abweichende
Arbeitsverzeichnisse kann die Projektwurzel vorgegeben werden:

```sh
mvn -Dsmarttestutils.projectRoot=/pfad/zum/projekt test
```

Getter müssen über den deklarierten Typ der Variablen erreichbar sein und deterministische
Werte liefern. Collections, Maps und Arrays benötigen eigene Assertions. Der Generator
liest zuerst alle Werte und ersetzt die Datei anschließend atomar. Scheitert ein Getter,
bleibt der Platzhalter für den nächsten Versuch erhalten.
