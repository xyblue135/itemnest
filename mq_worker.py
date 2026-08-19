from __future__ import annotations

import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

import aio_pika
from aio_pika import ExchangeType

import mq

BASE_DIR = Path(__file__).resolve().parent
EVENT_LOG = BASE_DIR / "data" / "mq_events.jsonl"


def append_event(event: dict) -> None:
    EVENT_LOG.parent.mkdir(parents=True, exist_ok=True)
    record = {**event, "consumed_at": datetime.now(timezone.utc).isoformat()}
    with EVENT_LOG.open("a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")


async def consume_forever() -> None:
    print(f"[RabbitMQ Worker] connecting: {mq.status()['url']}")
    connection = await aio_pika.connect_robust(mq.RABBITMQ_URL, client_properties={"connection_name": "ItemNest Worker"})
    async with connection:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=16)
        exchange = await channel.declare_exchange(mq.EXCHANGE_NAME, ExchangeType.TOPIC, durable=True)
        queue = await channel.declare_queue(mq.QUEUE_NAME, durable=True)
        await queue.bind(exchange, routing_key="inventory.#")
        print(f"[RabbitMQ Worker] consuming queue={mq.QUEUE_NAME}; Ctrl+C to stop")
        async with queue.iterator() as iterator:
            async for message in iterator:
                async with message.process(requeue=True):
                    event = json.loads(message.body.decode("utf-8"))
                    append_event(event)
                    print(f"[RabbitMQ Worker] {event.get('event')} {event.get('event_id')}")


async def main() -> None:
    if not mq.RABBITMQ_ENABLED:
        print("[RabbitMQ Worker] 已通过环境变量禁用 RabbitMQ（ITEMNEST_RABBITMQ_ENABLED=0），Worker 退出。")
        return
    while True:
        try:
            await consume_forever()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            print(f"[RabbitMQ Worker] disconnected: {type(exc).__name__}: {exc}; retry in 3s")
            await asyncio.sleep(3)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
