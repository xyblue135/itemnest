from __future__ import annotations
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any
from seed import SEED_DATA, CONTAINER_NOTES

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
DB_PATH = DATA_DIR / "inventory.db"


def row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    return {key: row[key] for key in row.keys()}


@contextmanager
def get_db():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db():
    with get_db() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS containers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                notes TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                container_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                quantity INTEGER,
                quantity_text TEXT NOT NULL DEFAULT '',
                condition TEXT NOT NULL DEFAULT '正常',
                notes TEXT NOT NULL DEFAULT '',
                tags TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(container_id) REFERENCES containers(id) ON DELETE RESTRICT
            );

            CREATE INDEX IF NOT EXISTS idx_items_container ON items(container_id);
            CREATE INDEX IF NOT EXISTS idx_items_name ON items(name);

            CREATE TABLE IF NOT EXISTS migrations (
                key TEXT PRIMARY KEY,
                applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            """
        )
        count = db.execute("SELECT COUNT(*) FROM containers").fetchone()[0]
        if count == 0:
            for container_name, items in SEED_DATA.items():
                cur = db.execute("INSERT INTO containers(name, notes) VALUES (?, ?)", (container_name, CONTAINER_NOTES.get(container_name, "")))
                container_id = cur.lastrowid
                for item in items:
                    db.execute(
                        """INSERT INTO items(container_id, name, quantity, quantity_text, condition, notes, tags)
                           VALUES (?, ?, ?, ?, ?, ?, ?)""",
                        (
                            container_id,
                            item["name"],
                            item.get("quantity", 1),
                            item.get("quantity_text", ""),
                            item.get("condition", "正常"),
                            item.get("notes", ""),
                            item.get("tags", ""),
                        ),
                    )

        # v0.2 清单增量迁移：即使用户沿用 v0.1 的 inventory.db，也只补充本次新增条目。
        migration_key = "2026-08-18-v0.2-inventory-expansion"
        applied = db.execute("SELECT 1 FROM migrations WHERE key = ?", (migration_key,)).fetchone()
        if not applied:
            additions = {
                "透明超大鱼缸箱子": SEED_DATA["透明超大鱼缸箱子"][2:],
                "纯甄酸奶箱子": [SEED_DATA["纯甄酸奶箱子"][-1]],
            }
            for container_name, items in additions.items():
                row = db.execute("SELECT id FROM containers WHERE name = ?", (container_name,)).fetchone()
                if not row:
                    cur = db.execute("INSERT INTO containers(name) VALUES (?)", (container_name,))
                    container_id = cur.lastrowid
                else:
                    container_id = row[0]
                for item in items:
                    exists = db.execute(
                        "SELECT 1 FROM items WHERE container_id = ? AND name = ?",
                        (container_id, item["name"]),
                    ).fetchone()
                    if exists:
                        continue
                    db.execute(
                        """INSERT INTO items(container_id, name, quantity, quantity_text, condition, notes, tags)
                           VALUES (?, ?, ?, ?, ?, ?, ?)""",
                        (
                            container_id,
                            item["name"],
                            item.get("quantity", 1),
                            item.get("quantity_text", ""),
                            item.get("condition", "正常"),
                            item.get("notes", ""),
                            item.get("tags", ""),
                        ),
                    )
            db.execute("INSERT INTO migrations(key) VALUES (?)", (migration_key,))

        # v0.3 清单增量迁移：新增两个常用盒子；黑色透明盒仅登记容器，不统计内部物品。
        migration_key = "2026-08-18-v0.3-common-boxes"
        applied = db.execute("SELECT 1 FROM migrations WHERE key = ?", (migration_key,)).fetchone()
        if not applied:
            for container_name in ("常用盒子【黑色透明】", "常用盒子【白色透明】"):
                note = CONTAINER_NOTES.get(container_name, "")
                row = db.execute("SELECT id FROM containers WHERE name = ?", (container_name,)).fetchone()
                if not row:
                    cur = db.execute("INSERT INTO containers(name, notes) VALUES (?, ?)", (container_name, note))
                    container_id = cur.lastrowid
                else:
                    container_id = row[0]
                    if note:
                        db.execute("UPDATE containers SET notes = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", (note, container_id))

                if container_name == "常用盒子【白色透明】":
                    for item in SEED_DATA[container_name]:
                        exists = db.execute(
                            "SELECT 1 FROM items WHERE container_id = ? AND name = ?",
                            (container_id, item["name"]),
                        ).fetchone()
                        if exists:
                            continue
                        db.execute(
                            """INSERT INTO items(container_id, name, quantity, quantity_text, condition, notes, tags)
                               VALUES (?, ?, ?, ?, ?, ?, ?)""",
                            (
                                container_id,
                                item["name"],
                                item.get("quantity", 1),
                                item.get("quantity_text", ""),
                                item.get("condition", "正常"),
                                item.get("notes", ""),
                                item.get("tags", ""),
                            ),
                        )
            db.execute("INSERT INTO migrations(key) VALUES (?)", (migration_key,))


def list_containers() -> list[dict[str, Any]]:
    with get_db() as db:
        rows = db.execute(
            """SELECT c.*, COUNT(i.id) AS item_count,
                      COALESCE(SUM(CASE WHEN i.id IS NULL THEN 0 ELSE COALESCE(i.quantity, 1) END), 0) AS quantity_sum
               FROM containers c LEFT JOIN items i ON i.container_id = c.id
               GROUP BY c.id ORDER BY c.id"""
        ).fetchall()
        return [row_to_dict(r) for r in rows]


def get_container(container_id: int):
    with get_db() as db:
        row = db.execute("SELECT * FROM containers WHERE id = ?", (container_id,)).fetchone()
        return row_to_dict(row) if row else None


def create_container(data: dict[str, Any]):
    with get_db() as db:
        cur = db.execute("INSERT INTO containers(name, notes) VALUES (?, ?)", (data["name"].strip(), data.get("notes", "").strip()))
        cid = cur.lastrowid
    return get_container(cid)


def update_container(container_id: int, data: dict[str, Any]):
    allowed = {"name", "notes"}
    fields = [(k, v.strip() if isinstance(v, str) else v) for k, v in data.items() if k in allowed]
    if not fields:
        return get_container(container_id)
    with get_db() as db:
        sets = ", ".join(f"{k} = ?" for k, _ in fields)
        db.execute(f"UPDATE containers SET {sets}, updated_at = CURRENT_TIMESTAMP WHERE id = ?", [v for _, v in fields] + [container_id])
    return get_container(container_id)


def delete_container(container_id: int):
    with get_db() as db:
        used = db.execute("SELECT COUNT(*) FROM items WHERE container_id = ?", (container_id,)).fetchone()[0]
        if used:
            raise ValueError("该箱子里还有物品，请先移动或删除物品")
        cur = db.execute("DELETE FROM containers WHERE id = ?", (container_id,))
        return cur.rowcount > 0


def list_items(q: str = "", container_id: int | None = None) -> list[dict[str, Any]]:
    sql = """SELECT i.*, c.name AS container_name FROM items i
             JOIN containers c ON c.id = i.container_id WHERE 1=1"""
    args: list[Any] = []
    if container_id:
        sql += " AND i.container_id = ?"
        args.append(container_id)
    if q.strip():
        like = f"%{q.strip()}%"
        sql += " AND (i.name LIKE ? OR i.notes LIKE ? OR i.tags LIKE ? OR i.condition LIKE ? OR c.name LIKE ?)"
        args.extend([like, like, like, like, like])
    sql += " ORDER BY i.updated_at DESC, i.id DESC"
    with get_db() as db:
        return [row_to_dict(r) for r in db.execute(sql, args).fetchall()]


def get_item(item_id: int):
    with get_db() as db:
        row = db.execute(
            "SELECT i.*, c.name AS container_name FROM items i JOIN containers c ON c.id=i.container_id WHERE i.id=?",
            (item_id,),
        ).fetchone()
        return row_to_dict(row) if row else None


def create_item(data: dict[str, Any]):
    with get_db() as db:
        cur = db.execute(
            """INSERT INTO items(container_id, name, quantity, quantity_text, condition, notes, tags)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (
                int(data["container_id"]),
                data["name"].strip(),
                data.get("quantity", 1),
                data.get("quantity_text", "").strip(),
                data.get("condition", "正常").strip() or "正常",
                data.get("notes", "").strip(),
                data.get("tags", "").strip(),
            ),
        )
        iid = cur.lastrowid
    return get_item(iid)


def update_item(item_id: int, data: dict[str, Any]):
    allowed = {"container_id", "name", "quantity", "quantity_text", "condition", "notes", "tags"}
    fields = []
    for k, v in data.items():
        if k not in allowed:
            continue
        if isinstance(v, str):
            v = v.strip()
        fields.append((k, v))
    if not fields:
        return get_item(item_id)
    with get_db() as db:
        sets = ", ".join(f"{k} = ?" for k, _ in fields)
        db.execute(f"UPDATE items SET {sets}, updated_at = CURRENT_TIMESTAMP WHERE id = ?", [v for _, v in fields] + [item_id])
    return get_item(item_id)


def delete_item(item_id: int):
    with get_db() as db:
        cur = db.execute("DELETE FROM items WHERE id = ?", (item_id,))
        return cur.rowcount > 0


def summary():
    with get_db() as db:
        container_count = db.execute("SELECT COUNT(*) FROM containers").fetchone()[0]
        item_count = db.execute("SELECT COUNT(*) FROM items").fetchone()[0]
        quantity_sum = db.execute("SELECT COALESCE(SUM(COALESCE(quantity, 1)), 0) FROM items").fetchone()[0]
        special_count = db.execute("SELECT COUNT(*) FROM items WHERE condition <> '正常'").fetchone()[0]
    return {"containers": container_count, "items": item_count, "quantity": quantity_sum, "special": special_count}
