from __future__ import annotations
import json
import os
import re
from pathlib import Path
from typing import Any
import httpx
import db

BASE_DIR = Path(__file__).resolve().parent
SETTINGS_PATH = BASE_DIR / "data" / "ai_settings.json"
DEFAULT_BASE_URL = "http://192.168.3.101:3001/v1"
DEFAULT_MODEL = "auto"


def _load_file_settings() -> dict[str, Any]:
    if not SETTINGS_PATH.exists():
        return {}
    try:
        return json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {}


def get_settings(include_key: bool = False) -> dict[str, Any]:
    stored = _load_file_settings()
    key = stored.get("api_key") or os.getenv("OPENAI_API_KEY", "")
    base_url = stored.get("base_url") or os.getenv("OPENAI_BASE_URL", DEFAULT_BASE_URL)
    model = stored.get("model") or os.getenv("OPENAI_MODEL", DEFAULT_MODEL)
    result = {"base_url": base_url, "model": model, "has_api_key": bool(key)}
    if include_key:
        result["api_key"] = key
    return result


def save_settings(data: dict[str, Any]):
    current = _load_file_settings()
    if "base_url" in data and data["base_url"]:
        current["base_url"] = data["base_url"].rstrip("/")
    if "model" in data and data["model"]:
        current["model"] = data["model"].strip()
    if data.get("api_key"):
        current["api_key"] = data["api_key"].strip()
    SETTINGS_PATH.parent.mkdir(parents=True, exist_ok=True)
    SETTINGS_PATH.write_text(json.dumps(current, ensure_ascii=False, indent=2), encoding="utf-8")
    return get_settings(False)


def _inventory_snapshot() -> str:
    containers = db.list_containers()
    items = db.list_items()
    lines = ["容器列表："]
    for c in containers:
        lines.append(f"- container_id={c['id']} | {c['name']} | {c['item_count']}类物品" + (f" | 备注={c['notes']}" if c.get('notes') else ""))
    lines.append("\n物品列表：")
    for x in items:
        qty = x.get("quantity_text") or (str(x.get("quantity")) if x.get("quantity") is not None else "未记录")
        extras = []
        if x.get("condition") and x["condition"] != "正常": extras.append(f"状态={x['condition']}")
        if x.get("notes"): extras.append(f"备注={x['notes']}")
        if x.get("tags"): extras.append(f"标签={x['tags']}")
        tail = " | " + " | ".join(extras) if extras else ""
        lines.append(f"- item_id={x['id']} | {x['name']} | 数量={qty} | 位置={x['container_name']} (container_id={x['container_id']}){tail}")
    return "\n".join(lines)


def _extract_json(text: str) -> dict[str, Any]:
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, re.S)
        if not match:
            raise
        return json.loads(match.group(0))


def _local_fallback(message: str) -> dict[str, Any]:
    message_l = message.lower().strip()

    # 如果用户明确提到某个箱子/盒子，优先返回该容器自身的信息。
    # 这样“内部不做统计”的容器不会因为没有 item 记录而被误判为空。
    container_matches = []
    for c in db.list_containers():
        name_l = c["name"].lower()
        parts = [p.strip().lower() for p in re.findall(r"[^【】]+", c["name"]) if p.strip()]
        score = 0
        if name_l in message_l:
            score += 20
        if len(parts) > 1 and all(p in message_l for p in parts):
            score += 16
        for p in parts:
            if len(p) >= 2 and p in message_l:
                score += 4
        if score:
            container_matches.append((score, c))
    container_matches.sort(key=lambda z: z[0], reverse=True)
    if container_matches and container_matches[0][0] >= 8:
        best_score, c = container_matches[0]
        # 只有在匹配明显指向容器时才抢占普通物品搜索。
        if any(word in message_l for word in ("盒子", "箱子", "里面", "内部", "收纳")):
            contained = db.list_items(container_id=c["id"])
            if c.get("notes") and not contained:
                reply = f"「{c['name']}」：{c['notes']}当前没有逐项登记的物品记录。"
            elif contained:
                names = "、".join(x["name"] for x in contained[:12])
                more = f"等，共 {len(contained)} 类" if len(contained) > 12 else f"，共 {len(contained)} 类"
                note = f"备注：{c['notes']} " if c.get("notes") else ""
                reply = f"「{c['name']}」中记录有：{names}{more}。{note}".strip()
            else:
                reply = f"「{c['name']}」目前没有登记物品。"
            return {"reply": reply, "action": None, "mode": "local"}

    items = db.list_items()
    matches = []
    for x in items:
        hay = " ".join([x["name"], x.get("tags", ""), x.get("notes", ""), x.get("container_name", "")]).lower()
        name = x["name"].lower()
        score = 0
        if name in message_l: score += 10
        for token in re.findall(r"[a-z0-9+-]{2,}|[\u4e00-\u9fff]{2,}", message_l):
            if token in hay: score += 2
            elif len(token) >= 4:
                for n in (4, 3, 2):
                    if any(token[i:i+n] in hay for i in range(max(0, len(token)-n+1))):
                        score += 1
                        break
        if score:
            matches.append((score, x))
    matches.sort(key=lambda z: z[0], reverse=True)
    top = [x for _, x in matches[:6]]
    if top:
        parts = []
        for x in top:
            qty = x.get("quantity_text") or x.get("quantity") or "未记录"
            parts.append(f"{x['name']}：在「{x['container_name']}」，数量 {qty}")
        reply = "我先用本地检索帮你找到这些可能相关的物品：\n" + "\n".join(f"• {p}" for p in parts)
    else:
        reply = "当前 AI 接口不可用，而且本地检索没有找到明显匹配。你可以换一个物品关键词，或到“设置”里检查 API 配置。"
    return {"reply": reply, "action": None, "mode": "local"}


async def chat(message: str) -> dict[str, Any]:
    settings = get_settings(True)
    if not settings.get("api_key"):
        return _local_fallback(message)

    system = f"""你是一个私人物品数据库助手。用户通过自然语言查询或提出数据库操作要求。
你只能依据下面的库存快照回答，不要编造不存在的物品。
对于查询，直接回答位置、数量、状态和备注。
对于任何会修改数据库的意图，你只能提出一个待确认 action，绝不能说已经修改成功。
如果目标不唯一或存在歧义，action 必须为 null，并在 reply 里追问最少必要信息。

严格只输出 JSON，不要 Markdown：
{{
  "reply": "中文回复",
  "action": null 或以下一种：
    {{"type":"add_item","data":{{"name":"...","container_id":1,"quantity":1,"quantity_text":"","condition":"正常","notes":"","tags":""}}}},
    {{"type":"update_item","item_id":1,"data":{{"name":"..."}}}},
    {{"type":"move_item","item_id":1,"container_id":2}},
    {{"type":"delete_item","item_id":1}},
    {{"type":"add_container","data":{{"name":"...","notes":""}}}}
}}
不要提出 delete_container 操作。数量不明确时可以 quantity=null，并把“一些/很多”等写入 quantity_text。

{_inventory_snapshot()}"""

    url = settings["base_url"].rstrip("/") + "/chat/completions"
    payload = {
        "model": settings["model"],
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": message},
        ],
        "temperature": 0.1,
    }
    headers = {"Authorization": f"Bearer {settings['api_key']}", "Content-Type": "application/json"}
    try:
        async with httpx.AsyncClient(timeout=45.0) as client:
            res = await client.post(url, json=payload, headers=headers)
            res.raise_for_status()
            body = res.json()
        content = body["choices"][0]["message"]["content"]
        result = _extract_json(content)
        result.setdefault("reply", "")
        result.setdefault("action", None)
        result["mode"] = "ai"
        return result
    except Exception as exc:
        fallback = _local_fallback(message)
        fallback["reply"] += f"\n\nAI 接口调用失败，已自动降级为本地检索（{type(exc).__name__}）。"
        return fallback


def execute_action(action: dict[str, Any]) -> dict[str, Any]:
    action_type = action.get("type")
    if action_type == "add_item":
        data = action.get("data") or {}
        if not data.get("name") or not data.get("container_id"):
            raise ValueError("新增物品缺少名称或箱子")
        if not db.get_container(int(data["container_id"])):
            raise ValueError("目标箱子不存在")
        item = db.create_item(data)
        return {"message": f"已新增「{item['name']}」到「{item['container_name']}」", "item": item}

    if action_type == "update_item":
        item_id = int(action.get("item_id") or 0)
        if not db.get_item(item_id): raise ValueError("物品不存在")
        item = db.update_item(item_id, action.get("data") or {})
        return {"message": f"已更新「{item['name']}」", "item": item}

    if action_type == "move_item":
        item_id = int(action.get("item_id") or 0)
        container_id = int(action.get("container_id") or 0)
        if not db.get_item(item_id): raise ValueError("物品不存在")
        if not db.get_container(container_id): raise ValueError("目标箱子不存在")
        item = db.update_item(item_id, {"container_id": container_id})
        return {"message": f"已把「{item['name']}」移动到「{item['container_name']}」", "item": item}

    if action_type == "delete_item":
        item_id = int(action.get("item_id") or 0)
        item = db.get_item(item_id)
        if not item: raise ValueError("物品不存在")
        db.delete_item(item_id)
        return {"message": f"已删除「{item['name']}」"}

    if action_type == "add_container":
        data = action.get("data") or {}
        if not data.get("name"): raise ValueError("箱子名称不能为空")
        c = db.create_container(data)
        return {"message": f"已新增箱子「{c['name']}」", "container": c}

    raise ValueError("不支持的 AI 操作")
