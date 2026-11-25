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
    assertThat(order.getCreated()).isEqualTo(LocalDate.parse("2024-02-29"));
    assertThat(order.getNumber()).isEqualTo("A-1");

    var customer = order.getCustomer();
    assertThat(customer.getLastLogin()).isEqualTo(LocalDateTime.parse("2024-02-29T08:15:30"));
    assertThat(customer.getName()).isEqualTo("Kunde");
    assertThat(customer.isVip()).isTrue();

    assertThat(customer.getAddresses()).contains("Hauptstr. 1");

    // Item::getCode ausgelassen (IllegalStateException: kein Code)
    assertThat(order.getItems())
            .extracting(Item::isActive, Item::getName, Item::getStatus)
            .contains(
                    Tuple.tuple(true, "Apfel", Status.OPEN),
                    Tuple.tuple(false, "Birne", Status.CLOSED));
}
```

Fehlende Imports (z. B. `import static org.assertj.core.api.Assertions.assertThat;`,
`import org.assertj.core.groups.Tuple;` oder verwendete Enums und Element-Klassen) werden direkt
unter der `package`-Deklaration eingefügt. Der generierte Code enthält keine Paketnamen.
Nur wenn ein einfacher Name bereits durch einen anderen Import oder eine Typdeklaration in der
Testdatei belegt ist, wird ausnahmsweise der voll qualifizierte Name geschrieben. Ist bereits ein
anderes statisches `assertThat` (z. B. Hamcrest) importiert, wird `Assertions.assertThat(...)` verwendet.

**Den Test erneut kompilieren und ausführen**, etwa mit einem zweiten `mvn test`.
Der erste Lauf erfasst die Werte per Reflection. Der zweite Lauf prüft sie.
Der Generator selbst verwendet ausschließlich das JDK.

## Unterstützte Properties und Werte

- Assertions werden nur für `String`, `boolean`/`Boolean`, Enums, `LocalDate` und `LocalDateTime`
  erzeugt (`isEqualTo`, `isTrue`/`isFalse`, `isNull`). Datumswerte werden über
  `LocalDate.parse("...")` bzw. `LocalDateTime.parse("...")` verglichen. Andere Werte (Zahlen,
  andere Datumstypen, `char`, Maps, Arrays, ...) werden ignoriert.
- Wirft ein Getter eine Exception, wird er übersprungen und im generierten Code durch einen
  einzeiligen Kommentar dokumentiert, z. B.
  `// bean.getFailure() ausgelassen (IllegalStateException: getter failed)`.
- Es werden ausschließlich öffentliche Properties verwendet: öffentliche JavaBean-Getter
  (`getName()`, `isEnabled()`), einschließlich geerbter Getter, Record-Komponenten und öffentliche
  Instanzfelder. Statische, geschützte, package-private und private Member werden ausgelassen.
- Member, die von Persistenz-Proxies oder Bytecode-Enhancement stammen, werden nicht ausgewertet:
  Bei Proxy-Klassen (z. B. `Entity$HibernateProxy$...`, ByteBuddy, Javassist, CGLIB) wird die
  eigentliche Entity-Klasse analysiert; Member mit `$` im Namen und Member, die in Klassen von
  JDK, Jakarta, Hibernate, ByteBuddy, Javassist, Spring, EclipseLink oder OpenJPA deklariert sind
  (z. B. `HibernateProxy.isSession()`), werden übersprungen.

Properties werden alphabetisch ausgegeben. Getter haben Vorrang vor gleichnamigen Feldern;
bei booleschen Properties hat `isX()` Vorrang vor `getX()`.

### Verschachtelte Objekte und Collections

Das Wurzelobjekt hat Tiefe 0. Verschachtelte Objekte werden rekursiv ausgewertet, höchstens drei
Property-Zugriffe tief, beispielsweise `object.getAddress().getCity().getName()`. Jedes Objekt
und jede Collection bildet eine eigene Gruppe von Assertions; Gruppen sind durch eine Leerzeile
getrennt. Wird der Ausdruck eines Objekts mehr als einmal benötigt, wird er zuerst in einer
lokalen Variable gespeichert (`var customer = order.getCustomer();`), damit der Getter nicht
wiederholt aufgerufen wird. Der Variablenname wird aus dem Property-Namen gebildet; kommt der
Name in der Testdatei bereits vor, wird eine Zahl angehängt (`address2`). Ein Objekt, für das bereits Assertions erzeugt wurden, wird nicht erneut ausgewertet,
so dass Referenzzyklen und gemeinsam genutzte Referenzen keine doppelten Assertions erzeugen.

Eine leere Collection wird mit `isEmpty()` geprüft. Sonst wird der Inhalt verglichen:

- Elemente vom Typ `String`, `Boolean` oder Enum: `contains(...)` mit allen Werten.
- Andere Elemente: `extracting(...)` mit Methodenreferenzen auf alle `String`-, `boolean`- und
  Enum-Properties der Element-Klasse und `contains(Tuple.tuple(...), ...)` (bei nur einer Property
  ohne `Tuple`). Öffentliche Felder werden über ein Lambda extrahiert.

Die Element-Klasse wird aus dem generischen Rückgabetyp des Getters bestimmt (`List<Item>`),
sonst aus der gemeinsamen Klasse der Elemente. Die Elemente selbst werden nicht weiter rekursiv
ausgewertet. Lässt sich der Inhalt nicht vergleichen (z. B. Zahlen oder `null`-Elemente), wird
stattdessen nur `hasSize(n)` erzeugt. Wirft ein Getter der Elemente eine Exception, wird diese
Property per Kommentar ausgelassen.

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
Werte liefern. Ist die Wurzel selbst eine Collection, muss die Variable mit dem passenden
Elementtyp deklariert sein (z. B. `List<Item>`). Maps und Arrays benötigen eigene Assertions. Der Generator
liest zuerst alle Werte und ersetzt die Datei anschließend atomar.
