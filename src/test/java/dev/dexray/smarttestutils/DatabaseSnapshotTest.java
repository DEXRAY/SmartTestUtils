package dev.dexray.smarttestutils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseSnapshotTest {

    private static final Pattern ROW_QUERY = Pattern.compile("SELECT \"([^\"]+)\", (\\S+) FROM \"([^\"]+)\"");

    private final Map<String, DatabaseSnapshot.TableInfo> tables = new LinkedHashMap<>();
    private final Map<String, Map<Long, Long>> rows = new LinkedHashMap<>();
    private final List<String> executedSql = new ArrayList<>();
    private DatabaseSnapshot snapshot;

    @BeforeEach
    void setUp() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenAnswer(invocation -> statement());
        snapshot = new DatabaseSnapshot(connection);

        addTable("PERSON", "ID", "VERSION");
        putRow("PERSON", 1L, 0L);
        putRow("PERSON", 2L, 3L);
        putRow("PERSON", 3L, null);

        addTable("LOG_ENTRY", "ID", null);
        putRow("LOG_ENTRY", 10L, null);
        putRow("LOG_ENTRY", 11L, null);
    }

    @Test
    void reportsInsertedUpdatedAndDeletedRowsPerTable() throws SQLException {
        snapshot.inMemorySnapshotStart();

        putRow("PERSON", 4L, 0L);
        putRow("PERSON", 2L, 4L);
        putRow("PERSON", 3L, 1L);
        rows.get("PERSON").remove(1L);
        putRow("LOG_ENTRY", 12L, null);

        DatabaseSnapshot.SnapshotDiff diff = snapshot.createDiff();

        assertThat(diff.tables()).containsOnlyKeys("LOG_ENTRY", "PERSON");
        DatabaseSnapshot.TableDiff person = diff.table("PERSON");
        assertThat(person.inserted()).containsExactly(4L);
        assertThat(person.updated()).containsExactly(2L, 3L);
        assertThat(person.deleted()).containsExactly(1L);
        DatabaseSnapshot.TableDiff log = diff.table("LOG_ENTRY");
        assertThat(log.inserted()).containsExactly(12L);
        assertThat(log.updated()).isEmpty();
        assertThat(log.deleted()).isEmpty();
    }

    @Test
    void returnsEmptyDiffWhenNothingChanged() throws SQLException {
        snapshot.inMemorySnapshotStart();

        assertThat(snapshot.createDiff().isEmpty()).isTrue();
    }

    @Test
    void ignoresUnchangedOrDecreasedVersions() throws SQLException {
        snapshot.inMemorySnapshotStart();

        putRow("PERSON", 2L, 1L);
        putRow("PERSON", 1L, 0L);

        assertThat(snapshot.createDiff().isEmpty()).isTrue();
    }

    @Test
    void doesNotReportUpdatesForTablesWithoutVersionColumn() throws SQLException {
        snapshot.inMemorySnapshotStart();

        assertThat(executedSql).contains("SELECT \"ID\", NULL FROM \"LOG_ENTRY\"");
        assertThat(snapshot.createDiff().table("LOG_ENTRY")).isNull();
    }

    @Test
    void ignoresTablesCreatedAfterSnapshot() throws SQLException {
        snapshot.inMemorySnapshotStart();

        addTable("NEW_TABLE", "ID", "VERSION");
        putRow("NEW_TABLE", 1L, 0L);

        assertThat(snapshot.createDiff().isEmpty()).isTrue();
    }

    @Test
    void reportsAllRowsAsDeletedWhenTableWasDropped() throws SQLException {
        snapshot.inMemorySnapshotStart();

        tables.remove("LOG_ENTRY");
        rows.remove("LOG_ENTRY");

        DatabaseSnapshot.SnapshotDiff diff = snapshot.createDiff();
        assertThat(diff.tables()).containsOnlyKeys("LOG_ENTRY");
        assertThat(diff.table("LOG_ENTRY").deleted()).containsExactly(10L, 11L);
    }

    @Test
    void alwaysComparesAgainstStartSnapshot() throws SQLException {
        snapshot.inMemorySnapshotStart();

        putRow("PERSON", 4L, 0L);
        assertThat(snapshot.createDiff().table("PERSON").inserted()).containsExactly(4L);

        putRow("PERSON", 5L, 0L);
        assertThat(snapshot.createDiff().table("PERSON").inserted()).containsExactly(4L, 5L);
    }

    @Test
    void firstSnapshotStoresNoDiff() throws SQLException {
        snapshot.inMemorySnapshotStart();

        assertThat(snapshot.diffs()).isEmpty();
    }

    @Test
    void everyFurtherSnapshotStoresDiffToPreviousSnapshot() throws SQLException {
        snapshot.inMemorySnapshotStart();

        putRow("PERSON", 4L, 0L);
        putRow("PERSON", 2L, 4L);
        snapshot.inMemorySnapshotStart();

        rows.get("PERSON").remove(4L);
        putRow("LOG_ENTRY", 12L, null);
        snapshot.inMemorySnapshotStart();

        snapshot.inMemorySnapshotStart();

        List<DatabaseSnapshot.SnapshotDiff> diffs = snapshot.diffs();
        assertThat(diffs).hasSize(3);

        assertThat(diffs.get(0).tables()).containsOnlyKeys("PERSON");
        assertThat(diffs.get(0).table("PERSON").inserted()).containsExactly(4L);
        assertThat(diffs.get(0).table("PERSON").updated()).containsExactly(2L);
        assertThat(diffs.get(0).table("PERSON").deleted()).isEmpty();

        assertThat(diffs.get(1).tables()).containsOnlyKeys("LOG_ENTRY", "PERSON");
        assertThat(diffs.get(1).table("PERSON").inserted()).isEmpty();
        assertThat(diffs.get(1).table("PERSON").updated()).isEmpty();
        assertThat(diffs.get(1).table("PERSON").deleted()).containsExactly(4L);
        assertThat(diffs.get(1).table("LOG_ENTRY").inserted()).containsExactly(12L);

        assertThat(diffs.get(2).isEmpty()).isTrue();
    }

    @Test
    void snapshotDiffReportsDroppedTablesAndIgnoresNewTables() throws SQLException {
        snapshot.inMemorySnapshotStart();

        tables.remove("LOG_ENTRY");
        rows.remove("LOG_ENTRY");
        addTable("NEW_TABLE", "ID", "VERSION");
        putRow("NEW_TABLE", 1L, 0L);
        snapshot.inMemorySnapshotStart();

        DatabaseSnapshot.SnapshotDiff diff = snapshot.diffs().get(0);
        assertThat(diff.tables()).containsOnlyKeys("LOG_ENTRY");
        assertThat(diff.table("LOG_ENTRY").deleted()).containsExactly(10L, 11L);
    }

    @Test
    void createDiffComparesAgainstLatestSnapshot() throws SQLException {
        snapshot.inMemorySnapshotStart();
        putRow("PERSON", 4L, 0L);
        snapshot.inMemorySnapshotStart();

        putRow("PERSON", 5L, 0L);

        assertThat(snapshot.createDiff().table("PERSON").inserted()).containsExactly(5L);
        assertThat(snapshot.diffs()).hasSize(1);
    }

    @Test
    void storedDiffsCannotBeModified() throws SQLException {
        snapshot.inMemorySnapshotStart();
        snapshot.inMemorySnapshotStart();

        assertThatThrownBy(() -> snapshot.diffs().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot.diffs()).hasSize(1);
    }

    @Test
    void createDiffRequiresSnapshotStart() {
        assertThatThrownBy(snapshot::createDiff).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsOnlyUserTablesWithSingleNumericPrimaryKey() throws SQLException {
        snapshot.inMemorySnapshotStart();

        assertThat(executedSql).contains(DatabaseSnapshot.TABLES_SQL);
        assertThat(DatabaseSnapshot.TABLES_SQL)
                .contains("FROM user_tables")
                .contains("constraint_type = 'P'")
                .contains("pk.data_type = 'NUMBER'")
                .contains("x.position > 1");
    }

    private void addTable(String name, String primaryKey, String version) {
        tables.put(name, new DatabaseSnapshot.TableInfo(name, primaryKey, version));
        rows.put(name, new LinkedHashMap<>());
    }

    private void putRow(String table, Long id, Long version) {
        rows.get(table).put(id, version);
    }

    private Statement statement() throws SQLException {
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> query(invocation.getArgument(0)));
        return statement;
    }

    private ResultSet query(String sql) throws SQLException {
        executedSql.add(sql);
        List<Object[]> result = new ArrayList<>();
        if (sql.equals(DatabaseSnapshot.TABLES_SQL)) {
            tables.values().forEach(t -> result.add(new Object[] {t.name(), t.primaryKeyColumn(), t.versionColumn()}));
        } else {
            Matcher matcher = ROW_QUERY.matcher(sql);
            assertThat(matcher.matches()).as("unexpected SQL: %s", sql).isTrue();
            DatabaseSnapshot.TableInfo table = tables.get(matcher.group(3));
            if (table == null) {
                throw new SQLException("ORA-00942: table or view does not exist");
            }
            boolean withVersion = !matcher.group(2).equals("NULL");
            rows.get(table.name()).forEach((id, version) ->
                    result.add(new Object[] {id, withVersion ? version : null}));
        }
        return resultSet(result);
    }

    private static ResultSet resultSet(List<Object[]> data) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] cursor = {-1};
        boolean[] lastNull = {false};
        when(rs.next()).thenAnswer(invocation -> ++cursor[0] < data.size());
        when(rs.getString(anyInt())).thenAnswer(invocation -> {
            Object value = data.get(cursor[0])[(int) invocation.getArgument(0) - 1];
            lastNull[0] = value == null;
            return (String) value;
        });
        when(rs.getLong(anyInt())).thenAnswer(invocation -> {
            Object value = data.get(cursor[0])[(int) invocation.getArgument(0) - 1];
            lastNull[0] = value == null;
            return value == null ? 0L : (Long) value;
        });
        when(rs.wasNull()).thenAnswer(invocation -> lastNull[0]);
        return rs;
    }
}
