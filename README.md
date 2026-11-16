# SmartTestUtils

Eine kleine Java-Bibliothek, die den aktuellen Zustand eines Objekts als AssertJ-Assertions
direkt in den Quelltext eines Maven-Tests schreibt. Die Implementierung besteht aus einer
öffentlichen Klasse: `dev.dexray.smarttestutils.SmartTestUtils`.

## Voraussetzungen und Build

- JDK 17 oder neuer, Maven 3.9 oder neuer
- Testquellen unter `src/test/java`, mit UTF-8 und Debug-Informationen für Quelldatei und Zeilennummern
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

Im Test einen Platzhalter einfügen:

```java
import dev.dexray.smarttestutils.SmartTestUtils;

// innerhalb einer Testmethode:
Person object = new Person("TestName", 42);
SmartTestUtils.createAsserts(object);
```

Beim Ausführen wird ausschließlich die Platzhalter-Anweisung ersetzt, beispielsweise durch:

```java
assertThat(object).isNotNull();
assertThat(object.getAge()).isEqualTo(42);
assertThat(object.getName()).isEqualTo("TestName");
```

Den Import anschließend selbst ergänzen, falls er noch fehlt:

```java
import static org.assertj.core.api.Assertions.assertThat;
```

**Danach den Test erneut kompilieren und ausführen.** Der erste Durchlauf erfasst Werte und
ändert den Quelltext; erst der nächste Durchlauf überprüft die generierten Assertions.
Die Klasse verändert keine Imports. Die erzeugten Festwerte vor dem Übernehmen fachlich prüfen.

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
Referenzen: Wiederholte Referenzen erhalten `isSameAs(...)`, ihre Properties werden nicht erneut gelesen.

## Quelltext-Suche und Grenzen

Die aufrufende Klasse und Zeile werden über `StackWalker` ermittelt. Zuerst wird das zum
`target/test-classes`-Verzeichnis gehörende Maven-Modul geprüft, anschließend die Maven-Projektstruktur.
Auch verschachtelte Testklassen und mehrere Platzhalter auf verschiedenen Zeilen werden unterstützt.
Bei abweichenden Arbeitsverzeichnissen lässt sich die Suche explizit steuern:

```sh
mvn -Dsmarttestutils.projectRoot=/pfad/zum/projekt test
```

- Der Aufruf muss eine eigenständige Anweisung sein. Auch einzelne `if`-/Schleifen-Anweisungen
  und `Runnable`-Lambdas werden durch einen Block sicher erweitert.
- Das Argument muss eine Variable oder ein Feldpfad sein, z. B. `object` oder `this.object`.
  Methodenaufrufe wie `createAsserts(loadObject())` werden abgelehnt, damit spätere Assertions
  keine Seiteneffekte mehrfach auslösen.
- Getter müssen deterministisch sein und dürfen keine Seiteneffekte haben. Sie müssen über
  den deklarierten Typ der Variablen erreichbar sein. Zusätzliche Getter einer Laufzeit-Unterklasse
  erfordern gegebenenfalls eine passend typisierte Variable.
- Collections, Maps, Arrays und weitere nicht unterstützte JDK-Objekte benötigen eigene Assertions.
  Sie werden vor Erreichen der Tiefengrenze mit einer Fehlermeldung abgelehnt.
- Mehrere Platzhalter auf derselben Zeile sind mehrdeutig und werden abgelehnt. Derselbe Platzhalter
  darf pro JVM nur einmal ausgeführt werden; für Schleifen und parametrisierte Tests ist ein separater,
  einmaliger Erfassungslauf nötig.
- Vor dem Schreiben werden alle Werte erfasst und der erzeugte Quelltext geparst. Bei einem
  Getter-/Generierungsfehler bleibt die betreffende Ersetzung aus. Kommentare außerhalb der
  ersetzten Anweisung, Einrückungen und LF-/CRLF-Zeilenenden bleiben erhalten.
- Externe Änderungen seit einer Erfassung führen zum Abbruch. Die Datei wird atomar ersetzt;
  das Dateisystem muss atomare Umbenennungen unterstützen. Quelltextänderungen während eines
  Erfassungslaufs vermeiden und die Änderungen anschließend im Git-Diff prüfen. Nach manuellen
  Änderungen oder einer Neukompilierung den Testprozess neu starten.

Der Parser unterstützt Java-17-Syntax; neuere Java-Sprachkonstrukte in Testquellen sind nicht unterstützt.
