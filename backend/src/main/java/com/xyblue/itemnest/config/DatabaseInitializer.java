package com.xyblue.itemnest.config;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DatabaseInitializer(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        createBaseSchema();
        migrateHousehold();
        createV08Schema();

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM containers", Integer.class);
        if (count != null && count == 0) seedFreshDatabase();

        rebuildFtsIndex();
        jdbc.update("INSERT OR IGNORE INTO migrations(key) VALUES (?)", "2026-08-19-v0.8-household-history-lifecycle-fts");
    }

    private void createBaseSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS containers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                notes TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                container_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                quantity INTEGER,
                quantity_text TEXT NOT NULL DEFAULT '',
                condition TEXT NOT NULL DEFAULT '正常',
                notes TEXT NOT NULL DEFAULT '',
                tags TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(container_id) REFERENCES containers(id) ON DELETE RESTRICT
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_items_container ON items(container_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_items_name ON items(name)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_items_condition ON items(condition)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_items_updated_at ON items(updated_at DESC, id DESC)");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS migrations (
                key TEXT PRIMARY KEY,
                applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
    }

    private void migrateHousehold() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS household_members (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbc.update("INSERT OR IGNORE INTO household_members(id, name, sort_order) VALUES (1, '我', 1)");
        jdbc.update("INSERT OR IGNORE INTO household_members(id, name, sort_order) VALUES (2, '爸', 2)");
        jdbc.update("INSERT OR IGNORE INTO household_members(id, name, sort_order) VALUES (3, '妈', 3)");
        if (!hasColumn("containers", "owner_id")) {
            jdbc.execute("ALTER TABLE containers ADD COLUMN owner_id INTEGER NOT NULL DEFAULT 1");
        }
        jdbc.update("UPDATE containers SET owner_id = 1 WHERE owner_id IS NULL OR owner_id NOT IN (1,2,3)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_containers_owner ON containers(owner_id, id)");
    }

    private void createV08Schema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS operation_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                action_type TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id INTEGER,
                item_id INTEGER,
                container_id INTEGER,
                related_container_id INTEGER,
                owner_id INTEGER,
                source TEXT NOT NULL DEFAULT 'manual',
                description TEXT NOT NULL DEFAULT '',
                before_json TEXT,
                after_json TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                undone_at TEXT
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_history_container ON operation_history(container_id, created_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_history_related_container ON operation_history(related_container_id, created_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_history_owner ON operation_history(owner_id, created_at DESC)");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS item_lifecycle (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id INTEGER NOT NULL UNIQUE,
                lifecycle_type TEXT NOT NULL DEFAULT 'EXPIRY',
                start_date TEXT,
                expiry_date TEXT,
                remind_days INTEGER NOT NULL DEFAULT 7,
                notes TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(item_id) REFERENCES items(id) ON DELETE CASCADE
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_lifecycle_expiry ON item_lifecycle(expiry_date)");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS attachments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id INTEGER NOT NULL,
                kind TEXT NOT NULL DEFAULT 'file',
                filename TEXT NOT NULL,
                stored_name TEXT NOT NULL UNIQUE,
                mime_type TEXT NOT NULL DEFAULT 'application/octet-stream',
                size_bytes INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(item_id) REFERENCES items(id) ON DELETE CASCADE
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_attachments_item ON attachments(item_id, created_at DESC)");

        try {
            jdbc.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts5(
                    item_id UNINDEXED,
                    name,
                    notes,
                    tags,
                    condition,
                    container_name,
                    member_name,
                    tokenize='unicode61'
                )
                """);
        } catch (RuntimeException ignored) {
            // The normal xerial SQLite build includes FTS5. If a custom build does not,
            // InventoryRepository transparently falls back to LIKE search.
        }
    }

    private boolean hasColumn(String table, String column) {
        return jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
            .anyMatch(row -> column.equalsIgnoreCase(String.valueOf(row.get("name"))));
    }

    private void rebuildFtsIndex() {
        try {
            jdbc.update("DELETE FROM items_fts");
            jdbc.update("""
                INSERT INTO items_fts(rowid, item_id, name, notes, tags, condition, container_name, member_name)
                SELECT i.id, i.id, i.name, i.notes, i.tags, i.condition, c.name, m.name
                FROM items i
                JOIN containers c ON c.id = i.container_id
                JOIN household_members m ON m.id = c.owner_id
                """);
        } catch (RuntimeException ignored) {
            // FTS5 is an optimization, never a requirement for core inventory access.
        }
    }

    private void seedFreshDatabase() throws Exception {
        ClassPathResource resource = new ClassPathResource("seed-data.json");
        try (InputStream input = resource.getInputStream()) {
            SeedData seed = objectMapper.readValue(input, SeedData.class);
            for (SeedContainer container : seed.containers()) {
                jdbc.update("INSERT INTO containers(name, notes, owner_id) VALUES (?, ?, 1)", container.name(), safe(container.notes()));
                Long id = jdbc.queryForObject("SELECT id FROM containers WHERE name = ?", Long.class, container.name());
                for (SeedItem item : container.items()) {
                    jdbc.update("""
                        INSERT INTO items(container_id, name, quantity, quantity_text, condition, notes, tags)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, id, item.name(), item.quantity(), safe(item.quantityText()),
                            blankToDefault(item.condition(), "正常"), safe(item.notes()), safe(item.tags()));
                }
            }
            jdbc.update("INSERT OR IGNORE INTO migrations(key) VALUES (?)", "2026-08-18-v0.2-inventory-expansion");
            jdbc.update("INSERT OR IGNORE INTO migrations(key) VALUES (?)", "2026-08-18-v0.3-common-boxes");
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String blankToDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SeedData(List<SeedContainer> containers) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SeedContainer(String name, String notes, List<SeedItem> items) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SeedItem(String name, Integer quantity,
        @com.fasterxml.jackson.annotation.JsonProperty("quantity_text") String quantityText,
        String condition, String notes, String tags) {}
}
