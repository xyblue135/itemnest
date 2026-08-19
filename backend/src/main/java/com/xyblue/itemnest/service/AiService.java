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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xyblue.itemnest.repository.HistoryRepository;
import com.xyblue.itemnest.repository.InventoryRepository;
import com.xyblue.itemnest.repository.LifecycleRepository;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "auto";
    private static final int MAX_AI_CONTEXT_ITEMS = 24;

    private final InventoryRepository repository;
    private final LifecycleRepository lifecycle;
    private final HistoryRepository history;
    private final AttachmentService attachments;
    private final ObjectMapper objectMapper;
    private final Path settingsPath;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AiService(InventoryRepository repository, LifecycleRepository lifecycle, HistoryRepository history, AttachmentService attachments,
                     ObjectMapper objectMapper, Path itemNestDataDir) {
        this.repository = repository; this.lifecycle = lifecycle; this.history = history; this.attachments = attachments; this.objectMapper = objectMapper;
        this.settingsPath = itemNestDataDir.resolve("ai_settings.json");
    }

    public Map<String, Object> getSettings(boolean includeKey) {
        Map<String, Object> stored = loadFileSettings();
        String key = firstNonBlank(System.getenv("ITEMNEST_AI_API_KEY"), System.getenv("OPENAI_API_KEY"), string(stored.get("api_key")), "");
        String baseUrl = firstNonBlank(System.getenv("ITEMNEST_AI_BASE_URL"), System.getenv("OPENAI_BASE_URL"), string(stored.get("base_url")), DEFAULT_BASE_URL);
        String model = firstNonBlank(System.getenv("ITEMNEST_AI_MODEL"), System.getenv("OPENAI_MODEL"), string(stored.get("model")), DEFAULT_MODEL);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_url", baseUrl); result.put("model", model); result.put("has_api_key", !key.isBlank());
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
        } catch (Exception ex) { throw new IllegalStateException("保存 AI 设置失败: " + ex.getMessage(), ex); }
        return getSettings(false);
    }

    public Map<String, Object> chat(String message, List<Long> ownerIds, boolean lifecycleOnly) {
        List<Long> scope = sanitizeScope(ownerIds);
        Map<String, Object> settings = getSettings(true);
        String apiKey = string(settings.get("api_key"));
        if (apiKey.isBlank()) return localFallback(message, scope, lifecycleOnly);

        String system = """
            你是 ItemNest 家庭物品数据库助手。你必须严格遵守用户选择的查询范围。
            数据层已经先按家庭成员范围过滤，再做本地 FTS5 检索；你只能依据下面的候选数据回答，不得假装看到了范围外的数据。
            查询时优先回答：归属成员、箱子、数量、状态、生命周期日期、备注。
            如果候选不足，明确说没有找到足够匹配，不要编造。

            重要限制：ItemNest 当前大语言模型链路不支持视觉。图片和附件只保存在本地，下面的上下文不会包含图片、图片 Base64、文件路径、PDF/附件正文；绝不能声称读取过附件内容。

            修改数据库时只生成一个待确认 action，不能说已经执行。允许的 action：
            {"type":"add_item","data":{"name":"...","container_id":1,"quantity":1,"quantity_text":"","condition":"正常","notes":"","tags":""}}
            {"type":"update_item","item_id":1,"data":{"name":"..."}}
            {"type":"move_item","item_id":1,"container_id":2}
            {"type":"delete_item","item_id":1}
            {"type":"add_container","data":{"name":"...","notes":"","owner_id":1}}
            {"type":"set_lifecycle","item_id":1,"data":{"lifecycle_type":"EXPIRY","expiry_date":"YYYY-MM-DD","remind_days":7,"notes":""}}
            任何 action 都必须位于当前家庭成员 Scope 中；不确定目标时 action=null 并追问。

            严格只输出 JSON：{"reply":"中文回复","action":null或action对象}
            """ + buildContext(message, scope, lifecycleOnly);

        Map<String, Object> payload = Map.of(
            "model", settings.get("model"),
            "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", message)),
            "temperature", 0.1
        );

        try {
            String url = string(settings.get("base_url")).replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
            JsonNode body = objectMapper.readTree(response.body());
            Map<String, Object> result = extractJson(body.path("choices").path(0).path("message").path("content").asText(""));
            result.putIfAbsent("reply", ""); result.putIfAbsent("action", null); result.put("mode", "ai");
            return result;
        } catch (Exception ex) {
            Map<String, Object> fallback = localFallback(message, scope, lifecycleOnly);
            fallback.put("reply", fallback.get("reply") + "\n\nAI 接口调用失败，已自动降级到本地 FTS5 检索（" + ex.getClass().getSimpleName() + "）。");
            return fallback;
        }
    }

    public Map<String, Object> executeAction(Map<String, Object> action, List<Long> ownerIds) {
        List<Long> scope = sanitizeScope(ownerIds);
        String type = string(action.get("type"));
        switch (type) {
            case "add_item" -> {
                Map<String, Object> data = map(action.get("data"));
                require(nonBlank(data.get("name")) && data.get("container_id") != null, "新增物品缺少名称或箱子");
                Map<String, Object> container = requireContainerInScope(number(data.get("container_id")).longValue(), scope);
                normalizeItemDefaults(data);
                Map<String, Object> item = repository.createItem(data);
                history.record("ADD_ITEM", "item", id(item), id(item), idValue(item.get("container_id")), null, idValue(item.get("owner_id")), "ai",
                    "AI 新增物品「" + item.get("name") + "」到「" + container.get("name") + "」", null, item);
                return Map.of("message", "已新增「" + item.get("name") + "」到「" + item.get("container_name") + "」", "item", item);
            }
            case "update_item" -> {
                long itemId = number(action.get("item_id")).longValue(); Map<String, Object> before = requireItemInScope(itemId, scope);
                Map<String, Object> data = map(action.get("data"));
                if (data.containsKey("container_id")) requireContainerInScope(number(data.get("container_id")).longValue(), scope);
                Map<String, Object> item = repository.updateItem(itemId, data);
                history.record("UPDATE_ITEM", "item", itemId, itemId, idValue(item.get("container_id")), null, idValue(item.get("owner_id")), "ai",
                    "AI 修改物品「" + item.get("name") + "」", before, item);
                return Map.of("message", "已更新「" + item.get("name") + "」", "item", item);
            }
            case "move_item" -> {
                long itemId = number(action.get("item_id")).longValue(); long containerId = number(action.get("container_id")).longValue();
                Map<String, Object> before = requireItemInScope(itemId, scope); requireContainerInScope(containerId, scope);
                Map<String, Object> item = repository.updateItem(itemId, Map.of("container_id", containerId));
                history.record("MOVE_ITEM", "item", itemId, itemId, containerId, idValue(before.get("container_id")), idValue(item.get("owner_id")), "ai",
                    "AI 移动「" + item.get("name") + "」：" + before.get("container_name") + " → " + item.get("container_name"), before, item);
                return Map.of("message", "已把「" + item.get("name") + "」移动到「" + item.get("container_name") + "」", "item", item);
            }
            case "delete_item" -> {
                long itemId = number(action.get("item_id")).longValue(); Map<String, Object> item = new LinkedHashMap<>(requireItemInScope(itemId, scope));
                item.put("_attachments", attachments.snapshot(itemId));
                repository.deleteItem(itemId);
                history.record("DELETE_ITEM", "item", itemId, itemId, idValue(item.get("container_id")), null, idValue(item.get("owner_id")), "ai",
                    "AI 删除物品「" + item.get("name") + "」", item, null);
                return Map.of("message", "已删除「" + item.get("name") + "」");
            }
            case "add_container" -> {
                Map<String, Object> data = map(action.get("data")); require(nonBlank(data.get("name")), "箱子名称不能为空");
                long ownerId = data.get("owner_id") == null ? (scope.isEmpty() ? 1L : scope.getFirst()) : number(data.get("owner_id")).longValue();
                require(scope.isEmpty() || scope.contains(ownerId), "AI 不能在当前 Scope 之外创建箱子");
                require(repository.getMember(ownerId) != null, "家庭成员不存在"); data.put("owner_id", ownerId); data.putIfAbsent("notes", "");
                Map<String, Object> container = repository.createContainer(data);
                history.record("ADD_CONTAINER", "container", id(container), null, id(container), null, ownerId, "ai",
                    "AI 新增箱子「" + container.get("name") + "」", null, container);
                return Map.of("message", "已新增箱子「" + container.get("name") + "」", "container", container);
            }
            case "set_lifecycle" -> {
                long itemId = number(action.get("item_id")).longValue(); Map<String, Object> item = requireItemInScope(itemId, scope);
                Map<String, Object> before = lifecycle.getByItem(itemId); Map<String, Object> after = lifecycle.upsert(itemId, map(action.get("data")));
                history.record("UPDATE_LIFECYCLE", "lifecycle", itemId, itemId, idValue(item.get("container_id")), null, idValue(item.get("owner_id")), "ai",
                    "AI 更新「" + item.get("name") + "」生命周期", before, after);
                return Map.of("message", "已更新「" + item.get("name") + "」生命周期", "lifecycle", after);
            }
            default -> throw new IllegalArgumentException("不支持的 AI 操作");
        }
    }

    private Map<String, Object> localFallback(String message, List<Long> scope, boolean lifecycleOnly) {
        if (lifecycleOnly || isLifecycleIntent(message)) {
            List<Map<String,Object>> life = lifecycle.list(scope, lifecycleStatusFor(message), 12);
            if (!life.isEmpty()) {
                StringBuilder reply = new StringBuilder("生命周期检索结果：\n");
                for (Map<String,Object> row : life) {
                    reply.append("• ").append(row.get("item_name")).append("（").append(row.get("owner_name")).append(" / ")
                        .append(row.get("container_name")).append("）：").append(row.get("lifecycle_type"));
                    if (nonBlank(row.get("expiry_date"))) reply.append(" ").append(row.get("expiry_date"));
                    if (row.get("days_left") != null) reply.append("，剩余 ").append(row.get("days_left")).append(" 天");
                    reply.append('\n');
                }
                return response(reply.toString().stripTrailing(), null, "local");
            }
        }
        List<Map<String, Object>> matches = repository.listItems(message, null, scope, lifecycleOnly, 8);
        if (isConditionIntent(message) && matches.isEmpty()) {
            matches = repository.listItems("", null, scope, lifecycleOnly, 100).stream()
                .filter(item -> nonBlank(item.get("condition")) && !"正常".equals(string(item.get("condition")))).limit(8).toList();
        }
        if (matches.isEmpty()) return response("本地 FTS5 检索没有找到匹配物品。可以换一个关键词，或调整 AI 的家庭成员/生命周期范围。", null, "local");
        StringBuilder reply = new StringBuilder("本地检索找到：\n");
        for (Map<String, Object> item : matches) {
            Object qty = nonBlank(item.get("quantity_text")) ? item.get("quantity_text") : item.get("quantity"); if (qty == null) qty = "未记录";
            reply.append("• ").append(item.get("name")).append("（").append(item.get("owner_name")).append("）：在「")
                .append(item.get("container_name")).append("」，数量 ").append(qty);
            if (nonBlank(item.get("condition")) && !"正常".equals(item.get("condition"))) reply.append("，状态 ").append(item.get("condition"));
            if (nonBlank(item.get("expiry_date"))) reply.append("，生命周期日期 ").append(item.get("expiry_date"));
            reply.append('\n');
        }
        return response(reply.toString().stripTrailing(), null, "local");
    }

    private String buildContext(String message, List<Long> scope, boolean lifecycleOnly) {
        StringBuilder out = new StringBuilder("\n当前 Scope：");
        List<Map<String, Object>> members = repository.listMembers().stream().filter(m -> scope.isEmpty() || scope.contains(id(m))).toList();
        out.append(members.stream().map(m -> string(m.get("name")) + "(id=" + m.get("id") + ")").reduce((a,b)->a+"、"+b).orElse("无"));
        if (lifecycleOnly) out.append("；只看具有生命周期记录的物品");
        out.append("\n可用箱子：\n");
        for (Map<String, Object> m : members) for (Map<String, Object> c : repository.listContainers(id(m)))
            out.append("- container_id=").append(c.get("id")).append(" | ").append(c.get("owner_name")).append("/ ").append(c.get("name")).append('\n');

        if (lifecycleOnly || isLifecycleIntent(message)) {
            List<Map<String,Object>> life = lifecycle.list(scope, lifecycleStatusFor(message), MAX_AI_CONTEXT_ITEMS);
            out.append("生命周期候选：\n");
            if (life.isEmpty()) out.append("- 无匹配生命周期记录\n");
            for (Map<String,Object> row : life) {
                out.append("- item_id=").append(row.get("item_id")).append(" | owner=").append(row.get("owner_name"))
                    .append(" | name=").append(row.get("item_name")).append(" | container=").append(row.get("container_name"))
                    .append(" | type=").append(row.get("lifecycle_type")).append(" | date=").append(row.get("expiry_date"))
                    .append(" | days_left=").append(row.get("days_left")).append(" | notes=").append(row.get("notes")).append('\n');
            }
        }
        List<Map<String, Object>> candidates = repository.listItems(message, null, scope, lifecycleOnly, MAX_AI_CONTEXT_ITEMS);
        if (isConditionIntent(message) && candidates.isEmpty()) {
            candidates = repository.listItems("", null, scope, lifecycleOnly, 100).stream()
                .filter(item -> nonBlank(item.get("condition")) && !"正常".equals(string(item.get("condition")))).limit(MAX_AI_CONTEXT_ITEMS).toList();
        }
        out.append("候选物品（本地检索后最多 ").append(MAX_AI_CONTEXT_ITEMS).append(" 条）：\n");
        if (candidates.isEmpty()) out.append("- 无匹配\n");
        for (Map<String, Object> item : candidates) appendItemContext(out, item);
        return out.toString();
    }

    private static void appendItemContext(StringBuilder lines, Map<String, Object> item) {
        Object qty = nonBlank(item.get("quantity_text")) ? item.get("quantity_text") : item.get("quantity"); if (qty == null) qty = "未记录";
        lines.append("- item_id=").append(item.get("id")).append(" | owner=").append(item.get("owner_name"))
            .append(" | name=").append(item.get("name")).append(" | quantity=").append(qty)
            .append(" | container=").append(item.get("container_name")).append("(id=").append(item.get("container_id")).append(")");
        if (nonBlank(item.get("condition")) && !"正常".equals(item.get("condition"))) lines.append(" | condition=").append(item.get("condition"));
        if (nonBlank(item.get("notes"))) lines.append(" | notes=").append(item.get("notes"));
        if (nonBlank(item.get("tags"))) lines.append(" | tags=").append(item.get("tags"));
        if (nonBlank(item.get("expiry_date"))) lines.append(" | lifecycle=").append(item.get("lifecycle_type")).append("@").append(item.get("expiry_date"));
        lines.append('\n');
    }

    private static boolean isLifecycleIntent(String message) {
        return List.of("生命周期", "到期", "过期", "保质", "有效期", "保修", "更换", "检查", "快坏", "该换").stream().anyMatch(message::contains);
    }
    private static boolean isConditionIntent(String message) {
        return List.of("状态", "异常", "损坏", "坏了", "不正常", "没电").stream().anyMatch(message::contains);
    }
    private static String lifecycleStatusFor(String message) {
        if (message.contains("快过期") || message.contains("即将过期") || message.contains("快到期") || message.contains("即将到期") || message.contains("该换")) return "DUE";
        if (message.contains("过期")) return "EXPIRED";
        if (message.contains("到期")) return "DUE";
        return "ALL";
    }

    private Map<String, Object> requireItemInScope(long itemId, List<Long> scope) {
        Map<String,Object> item=repository.getItem(itemId); require(item!=null,"物品不存在");
        require(scope.isEmpty() || scope.contains(idValue(item.get("owner_id"))), "该物品不在当前 AI Scope 中"); return item;
    }
    private Map<String, Object> requireContainerInScope(long containerId, List<Long> scope) {
        Map<String,Object> c=repository.getContainer(containerId); require(c!=null,"目标箱子不存在");
        require(scope.isEmpty() || scope.contains(idValue(c.get("owner_id"))), "目标箱子不在当前 AI Scope 中"); return c;
    }
    private List<Long> sanitizeScope(List<Long> values) {
        if(values==null||values.isEmpty()) return List.of();
        return values.stream().distinct().filter(id -> repository.getMember(id)!=null).toList();
    }

    private Map<String, Object> extractJson(String text) throws Exception {
        String clean = text.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        try { return objectMapper.readValue(clean, new TypeReference<>() {}); }
        catch (Exception ignored) { String json=firstJsonObject(clean); if(json==null) throw ignored; return objectMapper.readValue(json,new TypeReference<>(){}); }
    }
    private static String firstJsonObject(String text) {
        int start=-1,depth=0; boolean inString=false,escaped=false;
        for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(inString){if(escaped)escaped=false;else if(ch=='\\')escaped=true;else if(ch=='\"')inString=false;continue;}if(ch=='\"')inString=true;else if(ch=='{'){if(depth++==0)start=i;}else if(ch=='}'&&depth>0&&--depth==0)return text.substring(start,i+1);} return null;
    }
    private Map<String,Object> loadFileSettings(){if(!Files.exists(settingsPath))return Map.of();try{return objectMapper.readValue(Files.readString(settingsPath,StandardCharsets.UTF_8),new TypeReference<>(){});}catch(Exception ex){return Map.of();}}
    private static void normalizeItemDefaults(Map<String,Object> data){data.putIfAbsent("quantity",1);data.putIfAbsent("quantity_text","");data.putIfAbsent("condition","正常");data.putIfAbsent("notes","");data.putIfAbsent("tags","");}
    private static Map<String,Object> response(String reply,Object action,String mode){Map<String,Object> r=new LinkedHashMap<>();r.put("reply",reply);r.put("action",action);r.put("mode",mode);return r;}
    private static Map<String,Object> map(Object value){Map<String,Object> r=new LinkedHashMap<>();if(value instanceof Map<?,?> raw)raw.forEach((k,v)->r.put(String.valueOf(k),v));return r;}
    private static boolean nonBlank(Object value){return value!=null&&!String.valueOf(value).isBlank();}
    private static String string(Object value){return value==null?"":String.valueOf(value);}
    private static Number number(Object value){return value instanceof Number n?n:Long.parseLong(String.valueOf(value));}
    private static String firstNonBlank(String... values){for(String v:values)if(v!=null&&!v.isBlank())return v;return "";}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
    private static long id(Map<String,Object> map){return number(map.get("id")).longValue();}
    private static Long idValue(Object value){return value==null?null:number(value).longValue();}
}
