package com.xyblue.itemnest.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final InventoryRepository inventory;
    private final LifecycleRepository lifecycle;

    public HistoryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, InventoryRepository inventory, LifecycleRepository lifecycle) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.inventory = inventory;
        this.lifecycle = lifecycle;
    }

    public long record(String actionType, String entityType, Long entityId, Long itemId, Long containerId,
                       Long relatedContainerId, Long ownerId, String source, String description,
                       Object before, Object after) {
        jdbc.update("""
            INSERT INTO operation_history(action_type, entity_type, entity_id, item_id, container_id,
              related_container_id, owner_id, source, description, before_json, after_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, actionType, entityType, entityId, itemId, containerId, relatedContainerId, ownerId,
            source == null || source.isBlank() ? "manual" : source, description == null ? "" : description,
            json(before), json(after));
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    public List<Map<String, Object>> list(Long containerId, Long ownerId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM operation_history WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (containerId != null) {
            sql.append(" AND (container_id = ? OR related_container_id = ?)");
            args.add(containerId); args.add(containerId);
        }
        if (ownerId != null) { sql.append(" AND owner_id = ?"); args.add(ownerId); }
        sql.append(" ORDER BY id DESC LIMIT ?"); args.add(Math.max(1, Math.min(limit, 200)));
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        rows.forEach(row -> row.put("can_undo", row.get("undone_at") == null && !"attachment".equals(String.valueOf(row.get("entity_type")))));
        return rows;
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM operation_history WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> undo(long historyId) {
        Map<String, Object> h = get(historyId);
        if (h == null) throw new IllegalArgumentException("记录不存在");
        if (h.get("undone_at") != null) throw new IllegalArgumentException("该记录已经撤销过");
        ensureNoLaterConflictingOperation(h);
        String action = String.valueOf(h.get("action_type"));
        String entity = String.valueOf(h.get("entity_type"));
        Map<String, Object> before = parseMap(h.get("before_json"));
        Map<String, Object> after = parseMap(h.get("after_json"));

        switch (entity) {
            case "item" -> undoItem(action, before, after);
            case "container" -> undoContainer(action, before, after);
            case "batch" -> undoBatch(action, before);
            case "lifecycle" -> undoLifecycle(action, h, before);
            default -> throw new IllegalArgumentException("该记录暂不支持撤销");
        }
        jdbc.update("UPDATE operation_history SET undone_at = CURRENT_TIMESTAMP WHERE id = ?", historyId);
        return get(historyId);
    }


    private void undoLifecycle(String action, Map<String, Object> historyRow, Map<String, Object> before) {
        long itemId = InventoryRepository.number(historyRow.get("item_id")).longValue();
        if (inventory.getItem(itemId) == null) throw new IllegalArgumentException("物品已不存在，无法恢复生命周期");
        if (action.equals("DELETE_LIFECYCLE")) {
            if (before.isEmpty()) throw new IllegalArgumentException("缺少生命周期快照");
            lifecycle.upsert(itemId, before);
            return;
        }
        if (action.equals("UPDATE_LIFECYCLE")) {
            if (before.isEmpty()) lifecycle.delete(itemId); else lifecycle.upsert(itemId, before);
            return;
        }
        throw new IllegalArgumentException("该生命周期记录暂不支持撤销");
    }

    private void undoItem(String action, Map<String, Object> before, Map<String, Object> after) {
        long id = idFrom(before, after);
        if (action.equals("ADD_ITEM")) {
            if (inventory.getItem(id) != null) inventory.deleteItem(id);
            return;
        }
        if (action.equals("DELETE_ITEM")) {
            restoreDeletedItem(before);
            return;
        }
        if (before.isEmpty()) throw new IllegalArgumentException("缺少可恢复的旧数据");
        if (inventory.getItem(id) == null) throw new IllegalArgumentException("物品已不存在，无法安全撤销");
        inventory.updateItem(id, snapshotItemFields(before));
    }

    private void undoContainer(String action, Map<String, Object> before, Map<String, Object> after) {
        long id = idFrom(before, after);
        if (action.equals("ADD_CONTAINER")) {
            inventory.deleteContainer(id);
            return;
        }
        if (action.equals("DELETE_CONTAINER")) {
            if (before.isEmpty()) throw new IllegalArgumentException("缺少箱子快照");
            jdbc.update("INSERT INTO containers(id, name, notes, owner_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, before.get("name"), before.getOrDefault("notes", ""), before.getOrDefault("owner_id", 1),
                before.getOrDefault("created_at", nowLiteral()), before.getOrDefault("updated_at", nowLiteral()));
            return;
        }
        if (inventory.getContainer(id) == null) throw new IllegalArgumentException("箱子已不存在，无法安全撤销");
        Map<String, Object> data = new LinkedHashMap<>();
        copy(before, data, "name", "notes", "owner_id");
        inventory.updateContainer(id, data);
    }

    @SuppressWarnings("unchecked")
    private void undoBatch(String action, Map<String, Object> before) {
        Object raw = before.get("items");
        if (!(raw instanceof List<?> rows)) throw new IllegalArgumentException("批量记录缺少旧数据");
        if (action.equals("BATCH_CREATE")) {
            for (Object obj : rows) if (obj instanceof Map<?, ?> map) {
                Object id = map.get("id"); if (id != null && inventory.getItem(InventoryRepository.number(id).longValue()) != null) inventory.deleteItem(InventoryRepository.number(id).longValue());
            }
            return;
        }
        for (Object obj : rows) if (obj instanceof Map<?, ?> rawMap) {
            Map<String, Object> item = new LinkedHashMap<>(); rawMap.forEach((k,v) -> item.put(String.valueOf(k), v));
            long id = InventoryRepository.number(item.get("id")).longValue();
            if (inventory.getItem(id) != null) inventory.updateItem(id, snapshotItemFields(item));
        }
    }

    private void restoreDeletedItem(Map<String, Object> before) {
        long id = InventoryRepository.number(before.get("id")).longValue();
        if (inventory.getItem(id) != null) throw new IllegalArgumentException("原物品 ID 已被占用，无法安全恢复");
        jdbc.update("""
            INSERT INTO items(id, container_id, name, quantity, quantity_text, condition, notes, tags, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, before.get("container_id"), before.get("name"), before.get("quantity"),
            before.getOrDefault("quantity_text", ""), before.getOrDefault("condition", "正常"),
            before.getOrDefault("notes", ""), before.getOrDefault("tags", ""),
            before.getOrDefault("created_at", nowLiteral()), before.getOrDefault("updated_at", nowLiteral()));
        inventory.refreshFtsForItem(id);
        if (before.get("lifecycle_type") != null || before.get("expiry_date") != null) {
            Map<String, Object> life = new LinkedHashMap<>();
            life.put("lifecycle_type", before.getOrDefault("lifecycle_type", "EXPIRY"));
            life.put("start_date", before.get("lifecycle_start_date"));
            life.put("expiry_date", before.get("expiry_date"));
            life.put("remind_days", before.getOrDefault("remind_days", 7));
            life.put("notes", before.getOrDefault("lifecycle_notes", ""));
            lifecycle.upsert(id, life);
        }
        Object rawAttachments = before.get("_attachments");
        if (rawAttachments instanceof List<?> list) {
            for (Object obj : list) if (obj instanceof Map<?, ?> raw) {
                Map<String,Object> a = new LinkedHashMap<>(); raw.forEach((k,v)->a.put(String.valueOf(k),v));
                jdbc.update("INSERT OR IGNORE INTO attachments(id,item_id,kind,filename,stored_name,mime_type,size_bytes,created_at) VALUES (?,?,?,?,?,?,?,?)",
                    a.get("id"), id, a.getOrDefault("kind","file"), a.get("filename"), a.get("stored_name"),
                    a.getOrDefault("mime_type","application/octet-stream"), a.getOrDefault("size_bytes",0), a.getOrDefault("created_at", nowLiteral()));
            }
        }
    }

    private void ensureNoLaterConflictingOperation(Map<String, Object> h) {
        Object itemId = h.get("item_id");
        Object entityId = h.get("entity_id");
        String entityType = String.valueOf(h.get("entity_type"));
        long id = InventoryRepository.number(h.get("id")).longValue();
        Long later = 0L;
        if (itemId != null) {
            later = jdbc.queryForObject("SELECT COUNT(*) FROM operation_history WHERE id > ? AND undone_at IS NULL AND item_id = ?", Long.class, id, itemId);
        } else if (entityId != null && "container".equals(entityType)) {
            later = jdbc.queryForObject("SELECT COUNT(*) FROM operation_history WHERE id > ? AND undone_at IS NULL AND entity_type='container' AND entity_id = ?", Long.class, id, entityId);
        } else if ("batch".equals(entityType)) {
            Map<String,Object> snapshot = parseMap(h.get("before_json"));
            Object raw = snapshot.get("items");
            if (raw instanceof List<?> rows) {
                for (Object obj : rows) if (obj instanceof Map<?,?> row && row.get("id") != null) {
                    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM operation_history WHERE id > ? AND undone_at IS NULL AND item_id = ?", Long.class, id, row.get("id"));
                    if (count != null && count > 0) { later = count; break; }
                }
            }
        }
        if (later != null && later > 0) throw new IllegalArgumentException("该操作之后还有更新记录，请先撤销较新的操作");
    }

    private static Map<String, Object> snapshotItemFields(Map<String, Object> source) {
        Map<String, Object> data = new LinkedHashMap<>();
        copy(source, data, "container_id", "name", "quantity", "quantity_text", "condition", "notes", "tags");
        return data;
    }

    private static void copy(Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) if (from.containsKey(key)) to.put(key, from.get(key));
    }

    private long idFrom(Map<String, Object> a, Map<String, Object> b) {
        Object raw = a.get("id"); if (raw == null) raw = b.get("id");
        if (raw == null) throw new IllegalArgumentException("记录缺少实体 ID");
        return InventoryRepository.number(raw).longValue();
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("无法保存历史快照", ex); }
    }

    private Map<String, Object> parseMap(Object json) {
        if (json == null || String.valueOf(json).isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(String.valueOf(json), new TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalStateException("历史快照损坏", ex); }
    }

    private static String nowLiteral() { return java.time.LocalDateTime.now().toString(); }
}
