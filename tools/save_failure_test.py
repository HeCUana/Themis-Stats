#!/usr/bin/env python3
"""验证「保存异常」告警（`save_failure`）的端到端触发。

做法：在**服务端副本**上做，不碰主测试库。
  1. 把 `server/` 复制成 `server-sf-test/server/`；
  2. 起服、让假人上线、打开运行告警开关；
  3. 让**运行中**的数据库写入失败（破坏 `stats.db` 与 `stats.db-wal` 的内容）；
  4. 触发一次写入（假人移动 → 指标增量）；
  5. 查 `alert_log` 里有没有 `save_failure`，以及 `/themisstats admin status` 的降级描述。

为什么要用副本：破坏数据库是**不可逆**的，主测试库存着验收证据。

用法：python tools/save_failure_test.py
"""

from __future__ import annotations

import os
import shutil
import sqlite3
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from console_server import ConsoleServer  # noqa: E402

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

COPY_ROOT = os.path.join(ROOT, "server-sf-test")
COPY_SERVER = os.path.join(COPY_ROOT, "server")
DB = os.path.join(COPY_SERVER, "world", "themisstats", "stats.db")
BOT = "bot_a"


def copy_server() -> None:
    print("复制服务端到 %s ..." % COPY_ROOT)
    if os.path.isdir(COPY_ROOT):
        shutil.rmtree(COPY_ROOT, ignore_errors=True)
    os.makedirs(COPY_ROOT, exist_ok=True)
    shutil.copytree(os.path.join(ROOT, "server"), COPY_SERVER)
    print("复制完成")


def db_rows(sql: str) -> list[tuple]:
    try:
        con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\", "/"), uri=True, timeout=2.0)
        try:
            return con.execute(sql).fetchall()
        finally:
            con.close()
    except sqlite3.Error as exc:
        print("读库失败：%s" % exc)
        return []


def break_database() -> str:
    """破坏运行中的数据库，让下一次写入失败。返回做法说明。"""
    wal = DB + "-wal"
    # ① 把 WAL 截断：SQLite 下一次读事务会发现 WAL 头没了
    if os.path.exists(wal):
        with open(wal, "r+b") as handle:
            handle.truncate(0)
        return "截断了 %s" % os.path.basename(wal)
    # ② 没有 WAL 就把主库文件清零
    with open(DB, "r+b") as handle:
        handle.seek(0)
        handle.write(b"\x00" * 65536)
        handle.truncate(0)
    return "清空了 %s" % os.path.basename(DB)


def main() -> int:
    copy_server()
    server = ConsoleServer(root=COPY_ROOT)
    server.start()
    ok = False
    try:
        def run(cmd: str, settle: float = 1.2) -> str:
            server.send(cmd)
            time.sleep(settle)
            return server.drain()

        run("player %s spawn" % BOT)
        deadline = time.time() + 240
        online = False
        while time.time() < deadline:
            time.sleep(5)
            text = run("list", settle=1.0)
            if BOT in text:
                online = True
                break
        if not online:
            print("假人没进服，放弃")
            return 1
        run("carpet themisStatsBotAlerts true")
        time.sleep(2)

        print("破坏数据库：%s" % break_database())
        # 触发写入：假人动一动就会有指标增量
        run("player %s move forward" % BOT)
        time.sleep(3)
        run("player %s move" % BOT)

        fired = []
        deadline = time.time() + 60
        while time.time() < deadline:
            time.sleep(5)
            rows = db_rows("SELECT reason, message FROM alert_log "
                           "WHERE reason = 'save_failure' ORDER BY id DESC LIMIT 3")
            if rows:
                fired = rows
                break
        if fired:
            ok = True
            print("PASS  save_failure 告警已发出并落库：%s" % (fired[0][1],))
        else:
            print("FAIL  没有看到 save_failure 告警")
        text = run("themisstats admin status", settle=2.0)
        print("--- admin status 末尾 ---")
        for line in text.strip().splitlines()[-12:]:
            print("   " + line)
        text = run("themisstats admin status", settle=2.0)
        for line in text.splitlines():
            if "降级" in line or "保存异常" in line:
                print("   降级描述：" + line.strip())
    finally:
        server.stop()
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
