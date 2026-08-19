package com.xyblue.itemnest.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xyblue.itemnest.repository.InventoryRepository;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "auto";
    private static final int MAX_AI_CONTEXT_ITEMS = 24;

    private final InventoryRepository repository;
    private final ObjectMapper objectMapper;
    private final Path settingsPath;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public AiService(InventoryRepository repository, ObjectMapper objectMapper, Path itemNestDataDir) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.settingsPath = itemNestDataDir.resolve("ai_settings.json");
    }

    public Map<String, Object> getSettings(boolean includeKey) {
        Map<String, Object> stored = loadFileSettings();
        String key = firstNonBlank(System.getenv("ITEMNEST_AI_API_KEY"), System.getenv("OPENAI_API_KEY"), string(stored.get("api_key")), "");
        String baseUrl = firstNonBlank(System.getenv("ITEMNEST_AI_BASE_URL"), System.getenv("OPENAI_BASE_URL"), string(stored.get("base_url")), DEFAULT_BASE_URL);
        String model = firstNonBlank(System.getenv("ITEMNEST_AI_MODEL"), System.getenv("OPENAI_MODEL"), string(stored.get("model")), DEFAULT_MODEL);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_url", baseUrl);
        result.put("model", model);
        result.put("has_api_key", !key.isBlank());
        if (includeKey) result.put("api_key", key);
        return result;
    }

    public synchronized Map<String, Object> saveSettings(Map<String, Object> input) {
        Map<String, Object> current = new LinkedHashMap<>(loadFileSettings());
        if (nonBlank(input.get("base_url"))) current.put("base_url", string(input.get("base_url")).replaceAll("/+$", ""));
        if (nonBlank(input.get("model"))) current.put("model", string(input.get("model")).trim());
        if (nonBlank(input.get("api_key"))) current.put("api_key", string(input.get("api_key")).trim());
        try {
            Files.createDirectories(settingsPath.getParent());
            Files.writeString(settingsPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(current), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("保存 AI 设置失败: " + ex.getMessage(), ex);
        }
        return getSettings(false);
    }

    public Map<String, Object> chat(String message) {
        Map<String, Object> settings = getSettings(true);
        String apiKey = string(settings.get("api_key"));
        if (apiKey.isBlank()) return localFallback(message);

        String system = """
            你是一个私人物品数据库助手。用户通过自然语言查询或提出数据库操作要求。
            你只能依据下面的候选库存上下文回答，不要编造不存在的物品。
            对于查询，直接回答位置、数量、状态和备注。
            如果候选上下文不足以确定答案，要明确说明没有找到足够匹配，不要自行补全。
            对于任何会修改数据库的意图，你只能提出一个待确认 action，绝不能说已经修改成功。
            如果目标不唯一或存在歧义，action 必须为 null，并在 reply 里追问最少必要信息。

            严格只输出 JSON，不要 Markdown：
            {
              "reply": "中文回复",
              "action": null 或以下一种：
                {"type":"add_item","data":{"name":"...","container_id":1,"quantity":1,"quantity_text":"","condition":"正常","notes":"","tags":""}},
                {"type":"update_item","item_id":1,"data":{"name":"..."}},
                {"type":"move_item","item_id":1,"container_id":2},
                {"type":"delete_item","item_id":1},
                {"type":"add_container","data":{"name":"...","notes":""}}
            }
            不要提出 delete_container 操作。数量不明确时可以 quantity=null，并把“一些/很多”等写入 quantity_text。

            """ + inventoryContext(message);

        Map<String, Object> payload = Map.of(
            "model", settings.get("model"),
            "messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", message)
            ),
            "temperature", 0.1
        );

        try {
            String url = string(settings.get("base_url")).replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            String content = body.path("choices").path(0).path("message").path("content").asText("");
            Map<String, Object> result = extractJson(content);
            result.putIfAbsent("reply", "");
            result.putIfAbsent("action", null);
            result.put("mode", "ai");
            return result;
        } catch (Exception ex) {
            Map<String, Object> fallback = localFallback(message);
            fallback.put("reply", fallback.get("reply") + "\n\nAI 接口调用失败，已自动降级为本地检索（" + ex.getClass().getSimpleName() + "）。");
            return fallback;
        }
    }

    public Map<String, Object> executeAction(Map<String, Object> action) {
        String type = string(action.get("type"));
        switch (type) {
            case "add_item" -> {
                Map<String, Object> data = map(action.get("data"));
                require(nonBlank(data.get("name")) && data.get("container_id") != null, "新增物品缺少名称或箱子");
                long containerId = number(data.get("container_id")).longValue();
                require(repository.getContainer(containerId) != null, "目标箱子不存在");
                normalizeItemDefaults(data);
                Map<String, Object> item = repository.createItem(data);
                return Map.of("message", "已新增「" + item.get("name") + "」到「" + item.get("container_name") + "」", "item", item);
            }
            case "update_item" -> {
                long itemId = number(action.get("item_id")).longValue();
                require(repository.getItem(itemId) != null, "物品不存在");
                Map<String, Object> item = repository.updateItem(itemId, map(action.get("data")));
                return Map.of("message", "已更新「" + item.get("name") + "」", "item", item);
            }
            case "move_item" -> {
                long itemId = number(action.get("item_id")).longValue();
                long containerId = number(action.get("container_id")).longValue();
                require(repository.getItem(itemId) != null, "物品不存在");
                require(repository.getContainer(containerId) != null, "目标箱子不存在");
                Map<String, Object> item = repository.updateItem(itemId, Map.of("container_id", containerId));
                return Map.of("message", "已把「" + item.get("name") + "」移动到「" + item.get("container_name") + "」", "item", item);
            }
            case "delete_item" -> {
                long itemId = number(action.get("item_id")).longValue();
                Map<String, Object> item = repository.getItem(itemId);
                require(item != null, "物品不存在");
                repository.deleteItem(itemId);
                return Map.of("message", "已删除「" + item.get("name") + "」");
            }
            case "add_container" -> {
                Map<String, Object> data = map(action.get("data"));
                require(nonBlank(data.get("name")), "箱子名称不能为空");
                data.putIfAbsent("notes", "");
                Map<String, Object> container = repository.createContainer(data);
                return Map.of("message", "已新增箱子「" + container.get("name") + "」", "container", container);
            }
            default -> throw new IllegalArgumentException("不支持的 AI 操作");
        }
    }

    private Map<String, Object> localFallback(String message) {
        String lower = message.toLowerCase(Locale.ROOT).trim();

        List<Scored> containerMatches = new ArrayList<>();
        for (Map<String, Object> c : repository.listContainers()) {
            String name = string(c.get("name"));
            String nameLower = name.toLowerCase(Locale.ROOT);
            int score = nameLower.length() > 1 && lower.contains(nameLower) ? 20 : 0;
            for (String part : name.split("[【】]")) {
                String p = part.trim().toLowerCase(Locale.ROOT);
                if (p.length() >= 2 && lower.contains(p)) score += 4;
            }
            if (score > 0) containerMatches.add(new Scored(score, c));
        }
        containerMatches.sort(Comparator.comparingInt(Scored::score).reversed());
        if (!containerMatches.isEmpty() && containerMatches.getFirst().score() >= 8 &&
            List.of("盒子", "箱子", "里面", "内部", "收纳").stream().anyMatch(lower::contains)) {
            Map<String, Object> c = containerMatches.getFirst().value();
            long id = number(c.get("id")).longValue();
            List<Map<String, Object>> contained = repository.listItems("", id);
            String note = string(c.get("notes"));
            String reply;
            if (!note.isBlank() && contained.isEmpty()) {
                reply = "「" + c.get("name") + "」：" + note + "当前没有逐项登记的物品记录。";
            } else if (!contained.isEmpty()) {
                String names = contained.stream().limit(12).map(x -> string(x.get("name"))).reduce((a, b) -> a + "、" + b).orElse("");
                String more = contained.size() > 12 ? "等，共 " + contained.size() + " 类" : "，共 " + contained.size() + " 类";
                reply = "「" + c.get("name") + "」中记录有：" + names + more + "。" + (note.isBlank() ? "" : "备注：" + note);
            } else {
                reply = "「" + c.get("name") + "」目前没有登记物品。";
            }
            return response(reply, null, "local");
        }

        List<Scored> itemMatches = new ArrayList<>();
        for (Map<String, Object> item : repository.listItems("", null)) {
            String hay = (string(item.get("name")) + " " + string(item.get("tags")) + " " + string(item.get("notes")) + " " + string(item.get("container_name"))).toLowerCase(Locale.ROOT);
            int score = 0;
            String itemName = string(item.get("name")).toLowerCase(Locale.ROOT);
            if (!itemName.isBlank() && lower.contains(itemName)) score += 10;
            for (String token : tokens(lower)) {
                if (hay.contains(token)) score += 2;
                else if (token.length() >= 4 && containsAnySubstring(hay, token)) score += 1;
            }
            if (score > 0) itemMatches.add(new Scored(score, item));
        }
        itemMatches.sort(Comparator.comparingInt(Scored::score).reversed());
        if (!itemMatches.isEmpty()) {
            StringBuilder reply = new StringBuilder("我先用本地检索帮你找到这些可能相关的物品：\n");
            itemMatches.stream().limit(6).forEach(scored -> {
                Map<String, Object> item = scored.value();
                Object qty = nonBlank(item.get("quantity_text")) ? item.get("quantity_text") : item.get("quantity");
                if (qty == null) qty = "未记录";
                reply.append("• ").append(item.get("name")).append("：在「").append(item.get("container_name")).append("」，数量 ").append(qty).append("\n");
            });
            return response(reply.toString().stripTrailing(), null, "local");
        }
        return response("当前 AI 接口不可用，而且本地检索没有找到明显匹配。你可以换一个物品关键词，或到“设置”里检查 API 配置。", null, "local");
    }

    private String inventoryContext(String message) {
        String lower = message.toLowerCase(Locale.ROOT).trim();
        StringBuilder lines = new StringBuilder("容器列表：\n");
        for (Map<String, Object> c : repository.listContainers()) {
            lines.append("- container_id=").append(c.get("id")).append(" | ").append(c.get("name")).append(" | ")
                .append(c.get("item_count")).append("类物品");
            if (nonBlank(c.get("notes"))) lines.append(" | 备注=").append(c.get("notes"));
            lines.append('\n');
        }

        List<Map<String, Object>> allItems = repository.listItems("", null);
        List<Scored> matches = new ArrayList<>();
        boolean statusQuery = List.of("状态", "异常", "损坏", "坏了", "不正常").stream().anyMatch(lower::contains);
        for (Map<String, Object> item : allItems) {
            String itemName = string(item.get("name")).toLowerCase(Locale.ROOT);
            String condition = string(item.get("condition"));
            String hay = (string(item.get("name")) + " " + string(item.get("tags")) + " " +
                string(item.get("notes")) + " " + string(item.get("container_name")) + " " + condition)
                .toLowerCase(Locale.ROOT);
            int score = 0;
            if (!itemName.isBlank() && lower.contains(itemName)) score += 20;
            for (String token : tokens(lower)) {
                if (hay.contains(token)) score += 3;
                else if (token.length() >= 4 && containsAnySubstring(hay, token)) score += 1;
            }
            if (statusQuery && !condition.isBlank() && !"正常".equals(condition)) score += 8;
            if (score > 0) matches.add(new Scored(score, item));
        }

        matches.sort(Comparator.comparingInt(Scored::score).reversed());
        List<Map<String, Object>> selected;
        if (matches.isEmpty()) {
            selected = allItems.stream().limit(MAX_AI_CONTEXT_ITEMS).toList();
            lines.append("\n未找到明显关键词匹配，以下仅提供最近更新的少量物品作为候选：\n");
        } else {
            selected = matches.stream().limit(MAX_AI_CONTEXT_ITEMS).map(Scored::value).toList();
            lines.append("\n与当前请求最相关的物品候选（最多 ").append(MAX_AI_CONTEXT_ITEMS).append(" 条）：\n");
        }
        if (selected.isEmpty()) lines.append("- 当前没有登记物品\n");
        else selected.forEach(item -> appendItemContext(lines, item));
        return lines.toString();
    }

    private static void appendItemContext(StringBuilder lines, Map<String, Object> item) {
        Object qty = nonBlank(item.get("quantity_text")) ? item.get("quantity_text") : item.get("quantity");
        if (qty == null) qty = "未记录";
        lines.append("- item_id=").append(item.get("id")).append(" | ").append(item.get("name"))
            .append(" | 数量=").append(qty).append(" | 位置=").append(item.get("container_name"))
            .append(" (container_id=").append(item.get("container_id")).append(")");
        if (nonBlank(item.get("condition")) && !"正常".equals(item.get("condition"))) lines.append(" | 状态=").append(item.get("condition"));
        if (nonBlank(item.get("notes"))) lines.append(" | 备注=").append(item.get("notes"));
        if (nonBlank(item.get("tags"))) lines.append(" | 标签=").append(item.get("tags"));
        lines.append('\n');
    }

    private Map<String, Object> extractJson(String text) throws Exception {
        String clean = text.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        try {
            return objectMapper.readValue(clean, new TypeReference<>() {});
        } catch (Exception ignored) {
            String json = firstJsonObject(clean);
            if (json == null) throw ignored;
            return objectMapper.readValue(json, new TypeReference<>() {});
        }
    }

    private static String firstJsonObject(String text) {
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                if (depth++ == 0) start = i;
            } else if (ch == '}' && depth > 0 && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return null;
    }

    private Map<String, Object> loadFileSettings() {
        if (!Files.exists(settingsPath)) return Map.of();
        try {
            return objectMapper.readValue(Files.readString(settingsPath, StandardCharsets.UTF_8), new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile("[a-z0-9+\\-]{2,}|[\\p{IsHan}]{2,}").matcher(text);
        while (matcher.find()) tokens.add(matcher.group());
        return tokens;
    }

    private static boolean containsAnySubstring(String hay, String token) {
        for (int len : List.of(4, 3, 2)) {
            if (token.length() < len) continue;
            for (int i = 0; i <= token.length() - len; i++) {
                if (hay.contains(token.substring(i, i + len))) return true;
            }
        }
        return false;
    }

    private static void normalizeItemDefaults(Map<String, Object> data) {
        data.putIfAbsent("quantity", 1);
        data.putIfAbsent("quantity_text", "");
        data.putIfAbsent("condition", "正常");
        data.putIfAbsent("notes", "");
        data.putIfAbsent("tags", "");
    }

    private static Map<String, Object> response(String reply, Object action, String mode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        result.put("action", action);
        result.put("mode", mode);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static boolean nonBlank(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Number number(Object value) {
        if (value instanceof Number n) return n;
        return Long.parseLong(String.valueOf(value));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private record Scored(int score, Map<String, Object> value) {}
}
