from __future__ import annotations

import asyncio
import json
import os
import time
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlsplit, urlunsplit
from uuid import uuid4

try:
    import aio_pika
    from aio_pika import DeliveryMode, ExchangeType, Message
except ImportError:  # 让主程序在依赖缺失时仍能给出清晰状态，而不是直接崩溃
    aio_pika = None
    DeliveryMode = ExchangeType = Message = None

RABBITMQ_URL = os.getenv("ITEMNEST_RABBITMQ_URL", "amqp://guest:guest@127.0.0.1/")
RABBITMQ_ENABLED = os.getenv("ITEMNEST_RABBITMQ_ENABLED", "1").strip().lower() not in {"0", "false", "no", "off"}
EXCHANGE_NAME = os.getenv("ITEMNEST_RABBITMQ_EXCHANGE", "itemnest.events")
QUEUE_NAME = os.getenv("ITEMNEST_RABBITMQ_QUEUE", "itemnest.inventory.events")

_connection = None
_channel = None
_exchange = None
_connect_lock = asyncio.Lock()
_last_error = ""
_last_attempt_ts = 0.0
_RETRY_COOLDOWN = 20.0


def _safe_url() -> str:
    try:
        parts = urlsplit(RABBITMQ_URL)
        host = parts.hostname or "127.0.0.1"
        if parts.port:
            host = f"{host}:{parts.port}"
        if parts.username:
            host = f"{parts.username}:***@{host}"
        return urlunsplit((parts.scheme, host, parts.path or "/", "", ""))
    except Exception:
        return "amqp://***"


def status() -> dict[str, Any]:
    connected = bool(_connection and not _connection.is_closed and _channel and not _channel.is_closed and _exchange)
    return {
        "enabled": RABBITMQ_ENABLED,
        "connected": connected,
        "url": _safe_url(),
        "exchange": EXCHANGE_NAME,
        "queue": QUEUE_NAME,
        "last_error": _last_error,
        "client": "aio-pika",
    }


async def _ensure_connected() -> bool:
    global _connection, _channel, _exchange, _last_error, _last_attempt_ts
    if not RABBITMQ_ENABLED:
        return False
    if aio_pika is None:
        _last_error = "缺少 aio-pika 依赖"
        return False
    if _connection and not _connection.is_closed and _channel and not _channel.is_closed and _exchange:
        return True

    async with _connect_lock:
        if _connection and not _connection.is_closed and _channel and not _channel.is_closed and _exchange:
            return True
        # 失败冷却：RabbitMQ 不可用时不要每次发布都重连（会卡 ~2s 且留下孤儿重连任务）
        now = time.monotonic()
        if _last_attempt_ts and (now - _last_attempt_ts) < _RETRY_COOLDOWN:
            return False
        _last_attempt_ts = now
        try:
            # 用非 robust 连接：连接失败立即抛错，不会在后台无限重连
            _connection = await asyncio.wait_for(
                aio_pika.connect(RABBITMQ_URL, client_properties={"connection_name": "ItemNest API"}),
                timeout=2.0,
            )
            _channel = await _connection.channel(publisher_confirms=True)
            _exchange = await _channel.declare_exchange(EXCHANGE_NAME, ExchangeType.TOPIC, durable=True)
            queue = await _channel.declare_queue(QUEUE_NAME, durable=True)
            await queue.bind(_exchange, routing_key="inventory.#")
            _last_error = ""
            return True
        except Exception as exc:
            _connection = _channel = _exchange = None
            _last_error = f"{type(exc).__name__}: {exc}"
            return False


async def start() -> None:
    if not RABBITMQ_ENABLED:
        return
    ok = await _ensure_connected()
    if ok:
        print(f"[RabbitMQ] connected: {_safe_url()} exchange={EXCHANGE_NAME}")
    else:
        print(f"[RabbitMQ] unavailable, ItemNest will continue without MQ: {_last_error}")


async def stop() -> None:
    global _connection, _channel, _exchange
    if _connection and not _connection.is_closed:
        await _connection.close()
    _connection = _channel = _exchange = None


async def publish_event(event: str, payload: dict[str, Any]) -> bool:
    global _last_error, _connection, _channel, _exchange
    if not await _ensure_connected():
        return False

    envelope = {
        "event_id": str(uuid4()),
        "event": event,
        "source": "itemnest-api",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "payload": payload,
    }
    try:
        message = Message(
            json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            content_type="application/json",
            delivery_mode=DeliveryMode.PERSISTENT,
            message_id=envelope["event_id"],
            timestamp=datetime.now(timezone.utc),
        )
        await _exchange.publish(message, routing_key=event, mandatory=False)
        _last_error = ""
        return True
    except Exception as exc:
        _last_error = f"{type(exc).__name__}: {exc}"
        _connection = _channel = _exchange = None
        return False
