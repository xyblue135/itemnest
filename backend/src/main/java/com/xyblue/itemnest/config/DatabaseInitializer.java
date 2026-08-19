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

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM containers", Integer.class);
        if (count != null && count == 0) {
            seedFreshDatabase();
        }
    }

    private void seedFreshDatabase() throws Exception {
        ClassPathResource resource = new ClassPathResource("seed-data.json");
        try (InputStream input = resource.getInputStream()) {
            SeedData seed = objectMapper.readValue(input, SeedData.class);
            for (SeedContainer container : seed.containers()) {
                jdbc.update("INSERT INTO containers(name, notes) VALUES (?, ?)", container.name(), safe(container.notes()));
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SeedData(List<SeedContainer> containers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SeedContainer(String name, String notes, List<SeedItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SeedItem(
        String name,
        Integer quantity,
        @com.fasterxml.jackson.annotation.JsonProperty("quantity_text") String quantityText,
        String condition,
        String notes,
        String tags
    ) {}
}
