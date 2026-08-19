package com.xyblue.itemnest.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.xyblue.itemnest.exception.ApiException;
import com.xyblue.itemnest.repository.InventoryRepository;
import com.xyblue.itemnest.service.AiService;
import com.xyblue.itemnest.service.RabbitMqService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ItemNestController {
    private final InventoryRepository repository;
    private final AiService aiService;
    private final RabbitMqService mq;

    public ItemNestController(InventoryRepository repository, AiService aiService, RabbitMqService mq) {
        this.repository = repository;
        this.aiService = aiService;
        this.mq = mq;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return repository.summary();
    }

    @GetMapping("/containers")
    public List<Map<String, Object>> containers() {
        return repository.listContainers();
    }

    @PostMapping("/containers")
    public Map<String, Object> addContainer(@RequestBody Map<String, Object> payload) {
        requireName(payload.get("name"), "箱子名称不能为空");
        try {
            Map<String, Object> container = repository.createContainer(payload);
            mq.publishEvent("inventory.container.created", Map.of("container", container));
            return container;
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "箱子名称已存在");
        }
    }

    @PatchMapping("/containers/{id}")
    public Map<String, Object> patchContainer(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        if (repository.getContainer(id) == null) throw new ApiException(HttpStatus.NOT_FOUND, "箱子不存在");
        try {
            Map<String, Object> container = repository.updateContainer(id, payload);
            mq.publishEvent("inventory.container.updated", Map.of("container", container));
            return container;
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "箱子名称已存在");
        }
    }

    @DeleteMapping("/containers/{id}")
    public Map<String, Object> deleteContainer(@PathVariable long id) {
        Map<String, Object> container = repository.getContainer(id);
        if (container == null) throw new ApiException(HttpStatus.NOT_FOUND, "箱子不存在");
        try {
            repository.deleteContainer(id);
            mq.publishEvent("inventory.container.deleted", Map.of("container", container));
            return Map.of("ok", true);
        } catch (IllegalStateException ex) {
            throw new ApiException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @GetMapping("/items")
    public List<Map<String, Object>> items(
        @RequestParam(defaultValue = "") String q,
        @RequestParam(name = "container_id", required = false) Long containerId
    ) {
        if (q.length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "搜索关键词过长");
        return repository.listItems(q, containerId);
    }

    @PostMapping("/items")
    public Map<String, Object> addItem(@RequestBody Map<String, Object> payload) {
        requireName(payload.get("name"), "物品名称不能为空");
        long containerId = requireId(payload.get("container_id"), "目标箱子不存在");
        if (repository.getContainer(containerId) == null) throw new ApiException(HttpStatus.BAD_REQUEST, "目标箱子不存在");
        payload.putIfAbsent("quantity", 1);
        payload.putIfAbsent("quantity_text", "");
        payload.putIfAbsent("condition", "正常");
        payload.putIfAbsent("notes", "");
        payload.putIfAbsent("tags", "");
        Map<String, Object> item = repository.createItem(payload);
        mq.publishEvent("inventory.item.created", Map.of("item", item));
        return item;
    }

    @PatchMapping("/items/{id}")
    public Map<String, Object> patchItem(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        Map<String, Object> before = repository.getItem(id);
        if (before == null) throw new ApiException(HttpStatus.NOT_FOUND, "物品不存在");
        if (payload.containsKey("container_id") && payload.get("container_id") != null) {
            long containerId = requireId(payload.get("container_id"), "目标箱子不存在");
            if (repository.getContainer(containerId) == null) throw new ApiException(HttpStatus.BAD_REQUEST, "目标箱子不存在");
        }
        Map<String, Object> item = repository.updateItem(id, payload);
        String event = payload.containsKey("container_id") && payload.get("container_id") != null &&
            requireId(payload.get("container_id"), "目标箱子不存在") != ((Number) before.get("container_id")).longValue()
            ? "inventory.item.moved" : "inventory.item.updated";
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("before", before);
        eventPayload.put("item", item);
        mq.publishEvent(event, eventPayload);
        return item;
    }

    @DeleteMapping("/items/{id}")
    public Map<String, Object> deleteItem(@PathVariable long id) {
        Map<String, Object> item = repository.getItem(id);
        if (item == null) throw new ApiException(HttpStatus.NOT_FOUND, "物品不存在");
        repository.deleteItem(id);
        mq.publishEvent("inventory.item.deleted", Map.of("item", item));
        return Map.of("ok", true);
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return aiService.getSettings(false);
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> payload) {
        return aiService.saveSettings(payload);
    }

    @GetMapping("/mq/status")
    public Map<String, Object> mqStatus() {
        return mq.status();
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> payload) {
        String message = String.valueOf(payload.getOrDefault("message", "")).trim();
        if (message.isBlank() || message.length() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "消息不能为空或过长");
        return aiService.chat(message);
    }

    @PostMapping("/ai/execute")
    public Map<String, Object> executeAi(@RequestBody Map<String, Object> payload) {
        Object raw = payload.get("action");
        if (!(raw instanceof Map<?, ?> map)) throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 AI 操作");
        Map<String, Object> action = new LinkedHashMap<>();
        map.forEach((k, v) -> action.put(String.valueOf(k), v));
        try {
            Map<String, Object> result = aiService.executeAction(action);
            String type = String.valueOf(action.getOrDefault("type", "unknown"));
            String event = switch (type) {
                case "add_item" -> "inventory.item.created";
                case "update_item" -> "inventory.item.updated";
                case "move_item" -> "inventory.item.moved";
                case "delete_item" -> "inventory.item.deleted";
                case "add_container" -> "inventory.container.created";
                default -> "inventory.ai.executed";
            };
            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("via", "ai");
            eventPayload.put("action", action);
            eventPayload.put("result", result);
            mq.publishEvent(event, eventPayload);
            return result;
        } catch (IllegalArgumentException | DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static void requireName(Object value, String message) {
        if (value == null || String.valueOf(value).trim().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static long requireId(Object value, String message) {
        try {
            if (value instanceof Number n) return n.longValue();
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
