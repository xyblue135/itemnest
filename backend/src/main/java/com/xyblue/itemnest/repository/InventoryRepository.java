package com.xyblue.itemnest.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryRepository {
    private static final Set<String> ITEM_FIELDS = Set.of("container_id", "name", "quantity", "quantity_text", "condition", "notes", "tags");
    private static final Set<String> CONTAINER_FIELDS = Set.of("name", "notes", "owner_id");
    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> listMembers() {
        return jdbc.queryForList("SELECT * FROM household_members ORDER BY sort_order, id");
    }

    public Map<String, Object> getMember(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM household_members WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Map<String, Object>> listContainers() { return listContainers(null); }

    public List<Map<String, Object>> listContainers(Long ownerId) {
        String sql = """
            SELECT c.*, m.name AS owner_name, COUNT(i.id) AS item_count,
                   COALESCE(SUM(CASE WHEN i.id IS NULL THEN 0 ELSE COALESCE(i.quantity, 1) END), 0) AS quantity_sum
            FROM containers c
            JOIN household_members m ON m.id = c.owner_id
            LEFT JOIN items i ON i.container_id = c.id
            """ + (ownerId == null ? "" : " WHERE c.owner_id = ?") + " GROUP BY c.id ORDER BY c.owner_id, c.id";
        return ownerId == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, ownerId);
    }

    public Map<String, Object> getContainer(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT c.*, m.name AS owner_name,
                   (SELECT COUNT(*) FROM items i WHERE i.container_id = c.id) AS item_count,
                   (SELECT COALESCE(SUM(COALESCE(i.quantity, 1)),0) FROM items i WHERE i.container_id = c.id) AS quantity_sum
            FROM containers c JOIN household_members m ON m.id = c.owner_id WHERE c.id = ?
            """, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> createContainer(Map<String, Object> data) {
        String name = string(data.get("name")).trim();
        String notes = string(data.get("notes")).trim();
        long ownerId = data.get("owner_id") == null ? 1L : number(data.get("owner_id")).longValue();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO containers(name, notes, owner_id) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name); ps.setString(2, notes); ps.setLong(3, ownerId); return ps;
        }, key);
        return getContainer(key.getKey().longValue());
    }

    public Map<String, Object> updateContainer(long id, Map<String, Object> data) {
        updateDynamic("containers", id, data, CONTAINER_FIELDS);
        refreshFtsForContainer(id);
        return getContainer(id);
    }

    public boolean deleteContainer(long id) {
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE container_id = ?", Integer.class, id);
        if (used != null && used > 0) throw new IllegalStateException("该箱子里还有物品，请先移动或删除物品");
        return jdbc.update("DELETE FROM containers WHERE id = ?", id) > 0;
    }

    public List<Map<String, Object>> listItems(String query, Long containerId) {
        return listItems(query, containerId, List.of(), false, 500);
    }

    public List<Map<String, Object>> listItems(String query, Long containerId, List<Long> ownerIds, boolean lifecycleOnly, int limit) {
        if (query != null && !query.isBlank()) {
            List<Map<String, Object>> fts = searchItems(query, containerId, ownerIds, lifecycleOnly, limit);
            if (!fts.isEmpty()) return fts;
        }
        return listItemsLike(query, containerId, ownerIds, lifecycleOnly, limit);
    }

    private List<Map<String, Object>> listItemsLike(String query, Long containerId, List<Long> ownerIds, boolean lifecycleOnly, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT i.*, c.name AS container_name, c.owner_id, m.name AS owner_name,
                   l.lifecycle_type, l.start_date AS lifecycle_start_date, l.expiry_date,
                   l.remind_days, l.notes AS lifecycle_notes,
                   (SELECT COUNT(*) FROM attachments a WHERE a.item_id = i.id) AS attachment_count
            FROM items i
            JOIN containers c ON c.id = i.container_id
            JOIN household_members m ON m.id = c.owner_id
            LEFT JOIN item_lifecycle l ON l.item_id = i.id
            WHERE 1=1
            """);
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, containerId, ownerIds, lifecycleOnly);
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim() + "%";
            sql.append(" AND (i.name LIKE ? OR i.notes LIKE ? OR i.tags LIKE ? OR i.condition LIKE ? OR c.name LIKE ? OR m.name LIKE ?)");
            args.addAll(List.of(like, like, like, like, like, like));
        }
        sql.append(" ORDER BY i.updated_at DESC, i.id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 1000)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> searchItems(String query, Long containerId, List<Long> ownerIds, boolean lifecycleOnly, int limit) {
        String ftsQuery = buildFtsQuery(query);
        if (ftsQuery.isBlank()) return List.of();
        try {
            StringBuilder sql = new StringBuilder("""
                SELECT i.*, c.name AS container_name, c.owner_id, m.name AS owner_name,
                       l.lifecycle_type, l.start_date AS lifecycle_start_date, l.expiry_date,
                       l.remind_days, l.notes AS lifecycle_notes,
                       (SELECT COUNT(*) FROM attachments a WHERE a.item_id = i.id) AS attachment_count,
                       bm25(items_fts) AS search_rank
                FROM items_fts
                JOIN items i ON i.id = CAST(items_fts.item_id AS INTEGER)
                JOIN containers c ON c.id = i.container_id
                JOIN household_members m ON m.id = c.owner_id
                LEFT JOIN item_lifecycle l ON l.item_id = i.id
                WHERE items_fts MATCH ?
                """);
            List<Object> args = new ArrayList<>(); args.add(ftsQuery);
            appendFilters(sql, args, containerId, ownerIds, lifecycleOnly);
            sql.append(" ORDER BY bm25(items_fts), i.updated_at DESC LIMIT ?");
            args.add(Math.max(1, Math.min(limit, 200)));
            return jdbc.queryForList(sql.toString(), args.toArray());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private void appendFilters(StringBuilder sql, List<Object> args, Long containerId, List<Long> ownerIds, boolean lifecycleOnly) {
        if (containerId != null) { sql.append(" AND i.container_id = ?"); args.add(containerId); }
        if (ownerIds != null && !ownerIds.isEmpty()) {
            sql.append(" AND c.owner_id IN (").append(ownerIds.stream().map(x -> "?").collect(Collectors.joining(","))).append(")");
            args.addAll(ownerIds);
        }
        if (lifecycleOnly) sql.append(" AND l.item_id IS NOT NULL");
    }

    public Map<String, Object> getItem(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT i.*, c.name AS container_name, c.owner_id, m.name AS owner_name,
                   l.lifecycle_type, l.start_date AS lifecycle_start_date, l.expiry_date,
                   l.remind_days, l.notes AS lifecycle_notes,
                   (SELECT COUNT(*) FROM attachments a WHERE a.item_id = i.id) AS attachment_count
            FROM items i
            JOIN containers c ON c.id = i.container_id
            JOIN household_members m ON m.id = c.owner_id
            LEFT JOIN item_lifecycle l ON l.item_id = i.id
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
        long id = key.getKey().longValue(); refreshFtsForItem(id); return getItem(id);
    }

    public Map<String, Object> updateItem(long id, Map<String, Object> data) {
        updateDynamic("items", id, data, ITEM_FIELDS); refreshFtsForItem(id); return getItem(id);
    }

    public boolean deleteItem(long id) {
        deleteFtsForItem(id); return jdbc.update("DELETE FROM items WHERE id = ?", id) > 0;
    }

    public List<Map<String, Object>> createItemsBatch(long containerId, List<Map<String, Object>> rows) {
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            row.put("container_id", containerId);
            row.putIfAbsent("quantity", 1); row.putIfAbsent("quantity_text", ""); row.putIfAbsent("condition", "正常"); row.putIfAbsent("notes", ""); row.putIfAbsent("tags", "");
            created.add(createItem(row));
        }
        return created;
    }

    public List<Map<String, Object>> updateItemsBatch(List<Long> ids, Map<String, Object> data) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long id : ids) if (getItem(id) != null) result.add(updateItem(id, data));
        return result;
    }

    public Map<String, Object> summary() { return summary(List.of()); }

    public Map<String, Object> summary(List<Long> ownerIds) {
        String filter = ownerIds == null || ownerIds.isEmpty() ? "" : " WHERE c.owner_id IN (" + ownerIds.stream().map(x -> "?").collect(Collectors.joining(",")) + ")";
        Object[] args = ownerIds == null ? new Object[0] : ownerIds.toArray();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("containers", jdbc.queryForObject("SELECT COUNT(*) FROM containers c" + filter, Long.class, args));
        result.put("items", jdbc.queryForObject("SELECT COUNT(*) FROM items i JOIN containers c ON c.id=i.container_id" + filter, Long.class, args));
        result.put("quantity", jdbc.queryForObject("SELECT COALESCE(SUM(COALESCE(i.quantity,1)),0) FROM items i JOIN containers c ON c.id=i.container_id" + filter, Long.class, args));
        result.put("special", jdbc.queryForObject("SELECT COUNT(*) FROM items i JOIN containers c ON c.id=i.container_id" + filter + (filter.isEmpty() ? " WHERE" : " AND") + " i.condition <> '正常'", Long.class, args));
        return result;
    }

    public List<Map<String, Object>> memberSummary() {
        return jdbc.queryForList("""
            SELECT m.id, m.name,
                   COUNT(DISTINCT c.id) AS containers,
                   COUNT(i.id) AS items,
                   COALESCE(SUM(COALESCE(i.quantity, 1)),0) AS quantity
            FROM household_members m
            LEFT JOIN containers c ON c.owner_id = m.id
            LEFT JOIN items i ON i.container_id = c.id
            GROUP BY m.id ORDER BY m.sort_order, m.id
            """);
    }

    public void refreshFtsForItem(long itemId) {
        try {
            jdbc.update("DELETE FROM items_fts WHERE rowid = ?", itemId);
            jdbc.update("""
                INSERT INTO items_fts(rowid, item_id, name, notes, tags, condition, container_name, member_name)
                SELECT i.id, i.id, i.name, i.notes, i.tags, i.condition, c.name, m.name
                FROM items i JOIN containers c ON c.id=i.container_id JOIN household_members m ON m.id=c.owner_id
                WHERE i.id = ?
                """, itemId);
        } catch (RuntimeException ignored) {}
    }

    public void refreshFtsForContainer(long containerId) {
        try {
            List<Long> ids = jdbc.queryForList("SELECT id FROM items WHERE container_id = ?", Long.class, containerId);
            ids.forEach(this::refreshFtsForItem);
        } catch (RuntimeException ignored) {}
    }

    public void deleteFtsForItem(long itemId) {
        try { jdbc.update("DELETE FROM items_fts WHERE rowid = ?", itemId); } catch (RuntimeException ignored) {}
    }

    private static String buildFtsQuery(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher ascii = Pattern.compile("[A-Za-z0-9+_-]{2,}").matcher(query);
        while (ascii.find()) terms.add(ascii.group());
        Matcher han = Pattern.compile("[\\p{IsHan}]{2,}").matcher(query);
        while (han.find()) {
            String run = han.group();
            if (run.length() <= 5) terms.add(run);
            for (int len : List.of(4, 3, 2)) {
                if (run.length() < len) continue;
                for (int i = 0; i <= run.length() - len; i++) terms.add(run.substring(i, i + len));
            }
        }
        return terms.stream().limit(24)
            .map(term -> "\"" + term.replace("\"", "\"\"") + "\"*")
            .collect(Collectors.joining(" OR "));
    }

    private void updateDynamic(String table, long id, Map<String, Object> data, Set<String> allowed) {
        List<String> sets = new ArrayList<>(); List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!allowed.contains(entry.getKey())) continue;
            Object value = entry.getValue(); if (value instanceof String s) value = s.trim();
            sets.add(entry.getKey() + " = ?"); args.add(value);
        }
        if (sets.isEmpty()) return;
        args.add(id);
        jdbc.update("UPDATE " + table + " SET " + String.join(", ", sets) + ", updated_at = CURRENT_TIMESTAMP WHERE id = ?", args.toArray());
    }

    public static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    public static Number number(Object value) { return value instanceof Number n ? n : Long.parseLong(String.valueOf(value)); }
    public static Integer nullableInteger(Object value) { return value == null || String.valueOf(value).isBlank() ? null : number(value).intValue(); }
}
