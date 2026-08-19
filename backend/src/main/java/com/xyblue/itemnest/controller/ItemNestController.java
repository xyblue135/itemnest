package com.xyblue.itemnest.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.xyblue.itemnest.exception.ApiException;
import com.xyblue.itemnest.repository.HistoryRepository;
import com.xyblue.itemnest.repository.InventoryRepository;
import com.xyblue.itemnest.repository.LifecycleRepository;
import com.xyblue.itemnest.service.AiService;
import com.xyblue.itemnest.service.AttachmentService;
import com.xyblue.itemnest.service.RabbitMqService;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ItemNestController {
    private final InventoryRepository repository;
    private final HistoryRepository history;
    private final LifecycleRepository lifecycle;
    private final AttachmentService attachments;
    private final AiService aiService;
    private final RabbitMqService mq;

    public ItemNestController(InventoryRepository repository, HistoryRepository history, LifecycleRepository lifecycle,
                              AttachmentService attachments, AiService aiService, RabbitMqService mq) {
        this.repository = repository; this.history = history; this.lifecycle = lifecycle;
        this.attachments = attachments; this.aiService = aiService; this.mq = mq;
    }

    @GetMapping("/members")
    public List<Map<String, Object>> members() { return repository.listMembers(); }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(name="owner_id", required=false) List<Long> ownerIds) {
        return repository.summary(ownerIds == null ? List.of() : ownerIds);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return Map.of(
            "summary", repository.summary(),
            "members", repository.memberSummary(),
            "lifecycle", lifecycle.summary(),
            "recent_history", history.list(null, null, 8)
        );
    }

    @GetMapping("/containers")
    public List<Map<String, Object>> containers(@RequestParam(name="owner_id", required=false) Long ownerId) {
        return repository.listContainers(ownerId);
    }

    @PostMapping("/containers")
    public Map<String, Object> addContainer(@RequestBody Map<String, Object> payload) {
        requireName(payload.get("name"), "箱子名称不能为空");
        long ownerId = payload.get("owner_id") == null ? 1 : requireId(payload.get("owner_id"), "家庭成员不存在");
        requireMember(ownerId); payload.put("owner_id", ownerId);
        try {
            Map<String, Object> container = repository.createContainer(payload);
            history.record("ADD_CONTAINER", "container", id(container), null, id(container), null, ownerId, "manual",
                "新建箱子「" + container.get("name") + "」", null, container);
            mq.publishEvent("inventory.container.created", Map.of("container", container));
            return container;
        } catch (DataIntegrityViolationException ex) { throw new ApiException(HttpStatus.CONFLICT, "箱子名称已存在"); }
    }

    @PatchMapping("/containers/{id}")
    public Map<String, Object> patchContainer(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        Map<String, Object> before = requireContainer(id);
        if (payload.containsKey("owner_id")) requireMember(requireId(payload.get("owner_id"), "家庭成员不存在"));
        try {
            Map<String, Object> after = repository.updateContainer(id, payload);
            history.record("UPDATE_CONTAINER", "container", id, null, id, null, longValue(after.get("owner_id")), "manual",
                "修改箱子「" + after.get("name") + "」", before, after);
            mq.publishEvent("inventory.container.updated", Map.of("before", before, "container", after));
            return after;
        } catch (DataIntegrityViolationException ex) { throw new ApiException(HttpStatus.CONFLICT, "箱子名称已存在"); }
    }

    @DeleteMapping("/containers/{id}")
    public Map<String, Object> deleteContainer(@PathVariable long id) {
        Map<String, Object> before = requireContainer(id);
        try {
            repository.deleteContainer(id);
            history.record("DELETE_CONTAINER", "container", id, null, id, null, longValue(before.get("owner_id")), "manual",
                "删除空箱子「" + before.get("name") + "」", before, null);
            mq.publishEvent("inventory.container.deleted", Map.of("container", before));
            return Map.of("ok", true);
        } catch (IllegalStateException ex) { throw new ApiException(HttpStatus.CONFLICT, ex.getMessage()); }
    }

    @GetMapping("/items")
    public List<Map<String, Object>> items(@RequestParam(defaultValue="") String q,
        @RequestParam(name="container_id", required=false) Long containerId,
        @RequestParam(name="owner_id", required=false) List<Long> ownerIds,
        @RequestParam(name="lifecycle_only", defaultValue="false") boolean lifecycleOnly) {
        if (q.length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "搜索关键词过长");
        return repository.listItems(q, containerId, ownerIds == null ? List.of() : ownerIds, lifecycleOnly, 500);
    }

    @PostMapping("/items")
    public Map<String, Object> addItem(@RequestBody Map<String, Object> payload) {
        normalizeItemPayload(payload);
        Map<String, Object> item = repository.createItem(payload);
        history.record("ADD_ITEM", "item", id(item), id(item), idValue(item.get("container_id")), null,
            idValue(item.get("owner_id")), "manual", "新增物品「" + item.get("name") + "」到「" + item.get("container_name") + "」", null, item);
        mq.publishEvent("inventory.item.created", Map.of("item", item));
        return item;
    }

    @PatchMapping("/items/{id}")
    public Map<String, Object> patchItem(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        Map<String, Object> before = requireItem(id);
        if (payload.containsKey("container_id") && payload.get("container_id") != null) requireContainer(requireId(payload.get("container_id"), "目标箱子不存在"));
        Map<String, Object> after = repository.updateItem(id, payload);
        boolean moved = longValue(before.get("container_id")) != longValue(after.get("container_id"));
        history.record(moved ? "MOVE_ITEM" : "UPDATE_ITEM", "item", id, id,
            idValue(after.get("container_id")), moved ? idValue(before.get("container_id")) : null,
            idValue(after.get("owner_id")), "manual",
            moved ? "移动「" + after.get("name") + "」：" + before.get("container_name") + " → " + after.get("container_name") : "修改物品「" + after.get("name") + "」",
            before, after);
        mq.publishEvent(moved ? "inventory.item.moved" : "inventory.item.updated", Map.of("before", before, "item", after));
        return after;
    }

    @DeleteMapping("/items/{id}")
    public Map<String, Object> deleteItem(@PathVariable long id) {
        Map<String, Object> before = new LinkedHashMap<>(requireItem(id));
        before.put("_attachments", attachments.snapshot(id));
        repository.deleteItem(id);
        history.record("DELETE_ITEM", "item", id, id, idValue(before.get("container_id")), null, idValue(before.get("owner_id")), "manual",
            "删除物品「" + before.get("name") + "」", before, null);
        mq.publishEvent("inventory.item.deleted", Map.of("item", before));
        return Map.of("ok", true);
    }

    @PostMapping("/items/quick-entry")
    public Map<String, Object> quickEntry(@RequestBody Map<String, Object> payload) {
        long containerId = requireId(payload.get("container_id"), "目标箱子不存在");
        Map<String, Object> container = requireContainer(containerId);
        List<Map<String, Object>> rows = mapList(payload.get("items"));
        if (rows.isEmpty() || rows.size() > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "快速录入需要 1-100 条物品");
        rows.forEach(row -> { requireName(row.get("name"), "物品名称不能为空"); row.putIfAbsent("quantity", 1); });
        List<Map<String, Object>> created = repository.createItemsBatch(containerId, rows);
        history.record("BATCH_CREATE", "batch", null, null, containerId, null, idValue(container.get("owner_id")), "batch",
            "快速录入 " + created.size() + " 类物品到「" + container.get("name") + "」", Map.of("items", created), Map.of("items", created));
        mq.publishEvent("inventory.items.batch_created", Map.of("items", created));
        return Map.of("items", created, "count", created.size());
    }

    @PostMapping("/items/batch")
    public Map<String, Object> batchUpdate(@RequestBody Map<String, Object> payload) {
        List<Long> ids = longList(payload.get("ids"));
        if (ids.isEmpty() || ids.size() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择 1-200 个物品");
        Map<String, Object> data = map(payload.get("data"));
        if (data.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "没有要修改的字段");
        if (data.containsKey("container_id")) requireContainer(requireId(data.get("container_id"), "目标箱子不存在"));
        List<Map<String, Object>> before = ids.stream().map(repository::getItem).filter(x -> x != null).toList();
        List<Map<String, Object>> after = repository.updateItemsBatch(ids, data);
        Long containerId = data.containsKey("container_id") ? idValue(data.get("container_id")) : (after.isEmpty() ? null : idValue(after.getFirst().get("container_id")));
        Long related = before.isEmpty() ? null : idValue(before.getFirst().get("container_id"));
        Long owner = after.isEmpty() ? null : idValue(after.getFirst().get("owner_id"));
        history.record("BATCH_UPDATE", "batch", null, null, containerId, related, owner, "batch",
            "批量修改 " + after.size() + " 类物品", Map.of("items", before), Map.of("items", after));
        mq.publishEvent("inventory.items.batch_updated", Map.of("before", before, "items", after));
        return Map.of("items", after, "count", after.size());
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(name="container_id", required=false) Long containerId,
        @RequestParam(name="owner_id", required=false) Long ownerId,
        @RequestParam(defaultValue="50") int limit) { return history.list(containerId, ownerId, limit); }

    @PostMapping("/history/{id}/undo")
    public Map<String, Object> undo(@PathVariable long id) {
        try {
            Map<String, Object> result = history.undo(id);
            mq.publishEvent("inventory.history.undone", Map.of("history_id", id));
            return result;
        } catch (IllegalArgumentException | IllegalStateException ex) { throw new ApiException(HttpStatus.CONFLICT, ex.getMessage()); }
    }

    @GetMapping("/lifecycle")
    public List<Map<String, Object>> lifecycle(@RequestParam(name="owner_id", required=false) List<Long> ownerIds,
        @RequestParam(defaultValue="ALL") String status, @RequestParam(defaultValue="200") int limit) {
        return lifecycle.list(ownerIds == null ? List.of() : ownerIds, status, limit);
    }

    @PutMapping("/items/{itemId}/lifecycle")
    public Map<String, Object> saveLifecycle(@PathVariable long itemId, @RequestBody Map<String, Object> payload) {
        Map<String, Object> item = requireItem(itemId); Map<String, Object> before = lifecycle.getByItem(itemId);
        Map<String, Object> after = lifecycle.upsert(itemId, payload);
        history.record("UPDATE_LIFECYCLE", "lifecycle", itemId, itemId, idValue(item.get("container_id")), null, idValue(item.get("owner_id")), "manual",
            "更新「" + item.get("name") + "」生命周期", before, after);
        return after;
    }

    @DeleteMapping("/items/{itemId}/lifecycle")
    public Map<String, Object> deleteLifecycle(@PathVariable long itemId) {
        Map<String, Object> item = requireItem(itemId); Map<String, Object> before = lifecycle.getByItem(itemId);
        lifecycle.delete(itemId);
        history.record("DELETE_LIFECYCLE", "lifecycle", itemId, itemId, idValue(item.get("container_id")), null, idValue(item.get("owner_id")), "manual",
            "移除「" + item.get("name") + "」生命周期", before, null);
        return Map.of("ok", true);
    }

    @GetMapping("/items/{itemId}/attachments")
    public List<Map<String, Object>> itemAttachments(@PathVariable long itemId) { requireItem(itemId); return attachments.list(itemId); }

    @PostMapping(value="/items/{itemId}/attachments", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> addAttachment(@PathVariable long itemId, @RequestPart("file") MultipartFile file) {
        Map<String, Object> item = requireItem(itemId);
        try {
            Map<String, Object> saved = attachments.save(itemId, file);
            history.record("ADD_ATTACHMENT", "attachment", idValue(saved.get("id")), itemId, idValue(item.get("container_id")), null, idValue(item.get("owner_id")), "manual",
                "给「" + item.get("name") + "」添加附件「" + saved.get("filename") + "」", null, saved);
            return saved;
        } catch (IOException | IllegalArgumentException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    }

    @GetMapping("/attachments/{id}/content")
    public ResponseEntity<Resource> attachmentContent(@PathVariable long id) {
        try {
            Map<String, Object> meta = attachments.get(id); Resource resource = attachments.resource(id);
            if (meta == null || resource == null) throw new ApiException(HttpStatus.NOT_FOUND, "附件不存在");
            MediaType type; try { type = MediaType.parseMediaType(String.valueOf(meta.get("mime_type"))); } catch (Exception ex) { type = MediaType.APPLICATION_OCTET_STREAM; }
            return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + java.net.URLEncoder.encode(String.valueOf(meta.get("filename")), java.nio.charset.StandardCharsets.UTF_8))
                .body(resource);
        } catch (IOException ex) { throw new ApiException(HttpStatus.NOT_FOUND, "附件文件不存在"); }
    }

    @DeleteMapping("/attachments/{id}")
    public Map<String, Object> deleteAttachment(@PathVariable long id) {
        try { if (!attachments.delete(id)) throw new ApiException(HttpStatus.NOT_FOUND, "附件不存在"); return Map.of("ok", true); }
        catch (IOException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "删除附件失败"); }
    }

    @GetMapping("/settings") public Map<String, Object> getSettings() { return aiService.getSettings(false); }
    @PostMapping("/settings") public Map<String, Object> saveSettings(@RequestBody Map<String, Object> payload) { return aiService.saveSettings(payload); }
    @GetMapping("/mq/status") public Map<String, Object> mqStatus() { return mq.status(); }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> payload) {
        String message = String.valueOf(payload.getOrDefault("message", "")).trim();
        if (message.isBlank() || message.length() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "消息不能为空或过长");
        List<Long> ownerIds = longList(payload.get("owner_ids"));
        boolean lifecycleOnly = Boolean.TRUE.equals(payload.get("lifecycle_only"));
        return aiService.chat(message, ownerIds, lifecycleOnly);
    }

    @PostMapping("/ai/execute")
    public Map<String, Object> executeAi(@RequestBody Map<String, Object> payload) {
        Map<String, Object> action = map(payload.get("action"));
        if (action.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 AI 操作");
        List<Long> ownerIds = longList(payload.get("owner_ids"));
        try {
            Map<String, Object> result = aiService.executeAction(action, ownerIds);
            mq.publishEvent("inventory.ai.executed", Map.of("via", "ai", "action", action, "result", result));
            return result;
        } catch (IllegalArgumentException | DataIntegrityViolationException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    }

    private void normalizeItemPayload(Map<String, Object> payload) {
        requireName(payload.get("name"), "物品名称不能为空");
        long containerId = requireId(payload.get("container_id"), "目标箱子不存在"); requireContainer(containerId);
        payload.putIfAbsent("quantity", 1); payload.putIfAbsent("quantity_text", ""); payload.putIfAbsent("condition", "正常"); payload.putIfAbsent("notes", ""); payload.putIfAbsent("tags", "");
    }

    private Map<String, Object> requireContainer(long id) { Map<String,Object> value=repository.getContainer(id); if(value==null) throw new ApiException(HttpStatus.NOT_FOUND,"箱子不存在"); return value; }
    private Map<String, Object> requireItem(long id) { Map<String,Object> value=repository.getItem(id); if(value==null) throw new ApiException(HttpStatus.NOT_FOUND,"物品不存在"); return value; }
    private void requireMember(long id) { if(repository.getMember(id)==null) throw new ApiException(HttpStatus.BAD_REQUEST,"家庭成员不存在"); }
    private static void requireName(Object value,String message){ if(value==null||String.valueOf(value).trim().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,message); }
    private static long requireId(Object value,String message){ try{return longValue(value);}catch(Exception ex){throw new ApiException(HttpStatus.BAD_REQUEST,message);} }
    private static long id(Map<String,Object> map){return longValue(map.get("id"));}
    private static Long idValue(Object value){return value==null?null:longValue(value);}
    private static long longValue(Object value){return value instanceof Number n?n.longValue():Long.parseLong(String.valueOf(value));}

    private static Map<String,Object> map(Object raw){ Map<String,Object> result=new LinkedHashMap<>(); if(raw instanceof Map<?,?> m)m.forEach((k,v)->result.put(String.valueOf(k),v)); return result; }
    private static List<Map<String,Object>> mapList(Object raw){ List<Map<String,Object>> result=new ArrayList<>(); if(raw instanceof List<?> list) for(Object value:list){Map<String,Object> row=map(value);if(!row.isEmpty())result.add(row);} return result; }
    private static List<Long> longList(Object raw){ List<Long> result=new ArrayList<>(); if(raw instanceof List<?> list)for(Object x:list)try{result.add(longValue(x));}catch(Exception ignored){} else if(raw!=null)try{result.add(longValue(raw));}catch(Exception ignored){} return result; }
}
