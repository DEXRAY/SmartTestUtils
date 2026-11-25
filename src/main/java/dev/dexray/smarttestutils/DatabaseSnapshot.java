package dev.dexray.smarttestutils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

// Records which rows of the current Oracle user's tables were inserted, updated (VERSION increased)
// or deleted between inMemorySnapshotStart() and createDiff().
// Every further call of inMemorySnapshotStart() stores the diff to the previous snapshot (see diffs())
// and makes the new snapshot the reference for createDiff().
// Only tables with a single-column integral NUMBER primary key are considered.
public final class DatabaseSnapshot {

    public record TableDiff(String table, List<Long> inserted, List<Long> updated, List<Long> deleted) {
        public TableDiff {
            inserted = List.copyOf(inserted);
            updated = List.copyOf(updated);
            deleted = List.copyOf(deleted);
        }

        public boolean isEmpty() {
            return inserted.isEmpty() && updated.isEmpty() && deleted.isEmpty();
        }
    }

    public record SnapshotDiff(Map<String, TableDiff> tables) {
        public SnapshotDiff {
            tables = Collections.unmodifiableMap(new TreeMap<>(tables));
        }

        public boolean isEmpty() {
            return tables.isEmpty();
        }

        public TableDiff table(String tableName) {
            return tables.get(tableName);
        }
    }

    record TableInfo(String name, String primaryKeyColumn, String versionColumn) {
    }

    static final String TABLES_SQL = """
            SELECT t.table_name, pkc.column_name, v.column_name
              FROM user_tables t
              JOIN user_constraints c
                ON c.table_name = t.table_name AND c.constraint_type = 'P'
              JOIN user_cons_columns pkc
                ON pkc.constraint_name = c.constraint_name AND pkc.table_name = c.table_name
              JOIN user_tab_columns pk
                ON pk.table_name = t.table_name AND pk.column_name = pkc.column_name
              LEFT JOIN user_tab_columns v
                ON v.table_name = t.table_name AND UPPER(v.column_name) = 'VERSION' AND v.data_type = 'NUMBER'
             WHERE t.temporary = 'N'
               AND t.nested = 'NO'
               AND t.secondary = 'N'
               AND t.dropped = 'NO'
               AND (t.iot_type IS NULL OR t.iot_type = 'IOT')
               AND pk.data_type = 'NUMBER'
               AND NVL(pk.data_scale, 0) = 0
               AND NOT EXISTS (SELECT 1 FROM user_cons_columns x
                                WHERE x.constraint_name = c.constraint_name AND x.position > 1)
             ORDER BY t.table_name""";

    private static final int FETCH_SIZE = 1000;

    private final Connection connection;
    private Map<TableInfo, Map<Long, Long>> start;
    private final List<SnapshotDiff> diffs = new ArrayList<>();

    public DatabaseSnapshot(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public void inMemorySnapshotStart() throws SQLException {
        Map<TableInfo, Map<Long, Long>> snapshot = new HashMap<>();
        for (TableInfo table : loadTables().values()) {
            snapshot.put(table, loadRows(table));
        }
        if (start != null) {
            Map<String, Map<Long, Long>> after = new HashMap<>();
            snapshot.forEach((table, rows) -> after.put(table.name(), rows));
            diffs.add(diff(start, after));
        }
        start = snapshot;
    }

    // Diffs between consecutive snapshots in call order: diffs().get(0) compares the first with the
    // second snapshot. Unchanged intervals are stored as empty diffs, so the indices match the calls.
    public List<SnapshotDiff> diffs() {
        return List.copyOf(diffs);
    }

    public SnapshotDiff createDiff() throws SQLException {
        if (start == null) {
            throw new IllegalStateException("inMemorySnapshotStart() must be called before createDiff()");
        }
        Map<String, TableInfo> currentTables = loadTables();
        Map<String, Map<Long, Long>> current = new HashMap<>();
        for (TableInfo table : start.keySet()) {
            TableInfo now = currentTables.get(table.name());
            if (now != null) {
                current.put(table.name(), loadRows(now));
            }
        }
        return diff(start, current);
    }

    // Tables missing in "after" count as dropped (all rows deleted); tables only in "after" are ignored.
    private static SnapshotDiff diff(Map<TableInfo, Map<Long, Long>> before, Map<String, Map<Long, Long>> after) {
        Map<String, TableDiff> result = new HashMap<>();
        for (Map.Entry<TableInfo, Map<Long, Long>> entry : before.entrySet()) {
            TableInfo table = entry.getKey();
            TableDiff diff = diff(table, entry.getValue(), after.getOrDefault(table.name(), Map.of()));
            if (!diff.isEmpty()) {
                result.put(table.name(), diff);
            }
        }
        return new SnapshotDiff(result);
    }

    private static TableDiff diff(TableInfo table, Map<Long, Long> before, Map<Long, Long> after) {
        List<Long> inserted = new ArrayList<>();
        List<Long> updated = new ArrayList<>();
        List<Long> deleted = new ArrayList<>();
        for (Map.Entry<Long, Long> row : after.entrySet()) {
            Long id = row.getKey();
            if (!before.containsKey(id)) {
                inserted.add(id);
            } else if (table.versionColumn() != null && isIncreased(before.get(id), row.getValue())) {
                updated.add(id);
            }
        }
        for (Long id : before.keySet()) {
            if (!after.containsKey(id)) {
                deleted.add(id);
            }
        }
        Collections.sort(inserted);
        Collections.sort(updated);
        Collections.sort(deleted);
        return new TableDiff(table.name(), inserted, updated, deleted);
    }

    private static boolean isIncreased(Long oldVersion, Long newVersion) {
        return newVersion != null && (oldVersion == null || newVersion > oldVersion);
    }

    private Map<String, TableInfo> loadTables() throws SQLException {
        Map<String, TableInfo> tables = new TreeMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(TABLES_SQL)) {
            while (rs.next()) {
                TableInfo table = new TableInfo(rs.getString(1), rs.getString(2), rs.getString(3));
                tables.put(table.name(), table);
            }
        }
        return tables;
    }

    private Map<Long, Long> loadRows(TableInfo table) throws SQLException {
        String versionSelect = table.versionColumn() == null ? "NULL" : quote(table.versionColumn());
        String sql = "SELECT " + quote(table.primaryKeyColumn()) + ", " + versionSelect
                + " FROM " + quote(table.name());
        Map<Long, Long> rows = new HashMap<>();
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(FETCH_SIZE);
            try (ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    long version = rs.getLong(2);
                    rows.put(id, rs.wasNull() ? null : version);
                }
            }
        }
        return rows;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
