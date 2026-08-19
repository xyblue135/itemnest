package com.xyblue.itemnest.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryRepository {
    private static final Set<String> ITEM_FIELDS = Set.of("container_id", "name", "quantity", "quantity_text", "condition", "notes", "tags");
    private static final Set<String> CONTAINER_FIELDS = Set.of("name", "notes");

    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listContainers() {
        return jdbc.queryForList("""
            SELECT c.*, COUNT(i.id) AS item_count,
                   COALESCE(SUM(CASE WHEN i.id IS NULL THEN 0 ELSE COALESCE(i.quantity, 1) END), 0) AS quantity_sum
            FROM containers c
            LEFT JOIN items i ON i.container_id = c.id
            GROUP BY c.id
            ORDER BY c.id
            """);
    }

    public Map<String, Object> getContainer(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM containers WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> createContainer(Map<String, Object> data) {
        String name = string(data.get("name")).trim();
        String notes = string(data.get("notes")).trim();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO containers(name, notes) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, notes);
            return ps;
        }, key);
        return getContainer(key.getKey().longValue());
    }

    public Map<String, Object> updateContainer(long id, Map<String, Object> data) {
        updateDynamic("containers", id, data, CONTAINER_FIELDS);
        return getContainer(id);
    }

    public boolean deleteContainer(long id) {
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE container_id = ?", Integer.class, id);
        if (used != null && used > 0) {
            throw new IllegalStateException("该箱子里还有物品，请先移动或删除物品");
        }
        return jdbc.update("DELETE FROM containers WHERE id = ?", id) > 0;
    }

    public List<Map<String, Object>> listItems(String query, Long containerId) {
        StringBuilder sql = new StringBuilder("""
            SELECT i.*, c.name AS container_name
            FROM items i
            JOIN containers c ON c.id = i.container_id
            WHERE 1=1
            """);
        List<Object> args = new ArrayList<>();
        if (containerId != null) {
            sql.append(" AND i.container_id = ?");
            args.add(containerId);
        }
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim() + "%";
            sql.append(" AND (i.name LIKE ? OR i.notes LIKE ? OR i.tags LIKE ? OR i.condition LIKE ? OR c.name LIKE ?)");
            args.addAll(List.of(like, like, like, like, like));
        }
        sql.append(" ORDER BY i.updated_at DESC, i.id DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> getItem(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT i.*, c.name AS container_name
            FROM items i JOIN containers c ON c.id = i.container_id
            WHERE i.id = ?
            """, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> createItem(Map<String, Object> data) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO items(container_id, name, quantity, quantity_text, condition, notes, tags)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, number(data.get("container_id")).longValue());
            ps.setString(2, string(data.get("name")).trim());
            Integer quantity = nullableInteger(data.get("quantity"));
            if (quantity == null) ps.setObject(3, null); else ps.setInt(3, quantity);
            ps.setString(4, string(data.get("quantity_text")).trim());
            String condition = string(data.get("condition")).trim();
            ps.setString(5, condition.isBlank() ? "正常" : condition);
            ps.setString(6, string(data.get("notes")).trim());
            ps.setString(7, string(data.get("tags")).trim());
            return ps;
        }, key);
        return getItem(key.getKey().longValue());
    }

    public Map<String, Object> updateItem(long id, Map<String, Object> data) {
        updateDynamic("items", id, data, ITEM_FIELDS);
        return getItem(id);
    }

    public boolean deleteItem(long id) {
        return jdbc.update("DELETE FROM items WHERE id = ?", id) > 0;
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("containers", jdbc.queryForObject("SELECT COUNT(*) FROM containers", Long.class));
        result.put("items", jdbc.queryForObject("SELECT COUNT(*) FROM items", Long.class));
        result.put("quantity", jdbc.queryForObject("SELECT COALESCE(SUM(COALESCE(quantity, 1)), 0) FROM items", Long.class));
        result.put("special", jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE condition <> '正常'", Long.class));
        return result;
    }

    private void updateDynamic(String table, long id, Map<String, Object> data, Set<String> allowed) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!allowed.contains(entry.getKey())) continue;
            Object value = entry.getValue();
            if (value instanceof String s) value = s.trim();
            sets.add(entry.getKey() + " = ?");
            args.add(value);
        }
        if (sets.isEmpty()) return;
        args.add(id);
        String sql = "UPDATE " + table + " SET " + String.join(", ", sets) + ", updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        jdbc.update(sql, args.toArray());
    }

    public static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static Number number(Object value) {
        if (value instanceof Number n) return n;
        return Long.parseLong(String.valueOf(value));
    }

    public static Integer nullableInteger(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return number(value).intValue();
    }
}
