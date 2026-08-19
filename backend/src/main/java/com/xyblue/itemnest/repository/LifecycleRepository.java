package com.xyblue.itemnest.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LifecycleRepository {
    private final JdbcTemplate jdbc;
    public LifecycleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> getByItem(long itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM item_lifecycle WHERE item_id = ?", itemId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> upsert(long itemId, Map<String, Object> data) {
        String type = InventoryRepository.string(data.getOrDefault("lifecycle_type", "EXPIRY")).trim();
        String start = nullableText(data.get("start_date"));
        String expiry = nullableText(data.get("expiry_date"));
        int remind = data.get("remind_days") == null ? 7 : Math.max(0, InventoryRepository.number(data.get("remind_days")).intValue());
        String notes = InventoryRepository.string(data.get("notes")).trim();
        jdbc.update("""
            INSERT INTO item_lifecycle(item_id, lifecycle_type, start_date, expiry_date, remind_days, notes)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(item_id) DO UPDATE SET lifecycle_type=excluded.lifecycle_type, start_date=excluded.start_date,
              expiry_date=excluded.expiry_date, remind_days=excluded.remind_days, notes=excluded.notes, updated_at=CURRENT_TIMESTAMP
            """, itemId, type.isBlank() ? "EXPIRY" : type, start, expiry, remind, notes);
        return getByItem(itemId);
    }

    public boolean delete(long itemId) { return jdbc.update("DELETE FROM item_lifecycle WHERE item_id = ?", itemId) > 0; }

    public List<Map<String, Object>> list(List<Long> ownerIds, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT l.*, i.name AS item_name, i.container_id, c.name AS container_name, c.owner_id, m.name AS owner_name,
                   CAST(julianday(l.expiry_date) - julianday(date('now','localtime')) AS INTEGER) AS days_left,
                   CASE WHEN l.expiry_date IS NULL OR l.expiry_date='' THEN 'NO_DATE'
                        WHEN date(l.expiry_date) < date('now','localtime') THEN 'EXPIRED'
                        WHEN date(l.expiry_date) <= date('now','localtime', '+' || l.remind_days || ' day') THEN 'DUE'
                        ELSE 'ACTIVE' END AS lifecycle_status
            FROM item_lifecycle l
            JOIN items i ON i.id=l.item_id
            JOIN containers c ON c.id=i.container_id
            JOIN household_members m ON m.id=c.owner_id
            WHERE 1=1
            """);
        List<Object> args = new ArrayList<>();
        if (ownerIds != null && !ownerIds.isEmpty()) {
            sql.append(" AND c.owner_id IN (");
            for (int i=0;i<ownerIds.size();i++) { if (i>0) sql.append(','); sql.append('?'); args.add(ownerIds.get(i)); }
            sql.append(')');
        }
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
            switch (status.toUpperCase()) {
                case "EXPIRED" -> sql.append(" AND l.expiry_date IS NOT NULL AND date(l.expiry_date) < date('now','localtime')");
                case "DUE" -> sql.append(" AND l.expiry_date IS NOT NULL AND date(l.expiry_date) >= date('now','localtime') AND date(l.expiry_date) <= date('now','localtime', '+' || l.remind_days || ' day')");
                case "ACTIVE" -> sql.append(" AND l.expiry_date IS NOT NULL AND date(l.expiry_date) > date('now','localtime', '+' || l.remind_days || ' day')");
                default -> { }
            }
        }
        sql.append(" ORDER BY CASE WHEN l.expiry_date IS NULL OR l.expiry_date='' THEN 1 ELSE 0 END, date(l.expiry_date), l.id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> summary() {
        return Map.of(
            "expired", jdbc.queryForObject("SELECT COUNT(*) FROM item_lifecycle WHERE expiry_date IS NOT NULL AND date(expiry_date) < date('now','localtime')", Long.class),
            "due7", jdbc.queryForObject("SELECT COUNT(*) FROM item_lifecycle WHERE expiry_date IS NOT NULL AND date(expiry_date) BETWEEN date('now','localtime') AND date('now','localtime','+7 day')", Long.class),
            "due30", jdbc.queryForObject("SELECT COUNT(*) FROM item_lifecycle WHERE expiry_date IS NOT NULL AND date(expiry_date) BETWEEN date('now','localtime') AND date('now','localtime','+30 day')", Long.class),
            "total", jdbc.queryForObject("SELECT COUNT(*) FROM item_lifecycle", Long.class)
        );
    }

    private static String nullableText(Object value) {
        String s = InventoryRepository.string(value).trim(); return s.isBlank() ? null : s;
    }
}
