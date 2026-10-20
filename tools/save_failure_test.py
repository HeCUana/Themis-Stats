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


def hold_exclusive_lock():
    """独占锁住数据库，让服务端的写入报「database is locked」。

    试过的两种「破坏文件」的办法在本机都不管用：
    - 截断 WAL：SQLite 会把它当成空日志重建，数据库照常可用；
    - 清零主库文件：WAL 模式下最近改过的页都在 WAL 里，旧的页根本没被读，写入照样成功。

    所以改用**锁**：另开一个连接执行 `BEGIN EXCLUSIVE` 拿住写锁，
    服务端的后台写入线程就只能拿到 SQLITE_BUSY（`busy_timeout` 是 5000 ms）。
    这个办法**不改动任何文件**，最安全。
    """
    con = sqlite3.connect(DB, timeout=5)
    con.isolation_level = None
    con.execute("SELECT count(*) FROM total").fetchone()
    con.execute("BEGIN EXCLUSIVE")
    con.execute("SELECT count(*) FROM total").fetchone()
    return con


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

        print("独占锁住数据库，让服务端的写入失败……")
        lock = hold_exclusive_lock()
        # 触发写入：假人动一动就会有指标增量
        run("player %s move forward" % BOT)
        time.sleep(4)
        run("player %s move" % BOT)
        print("等待写入线程报错并触发告警……")

        # 注意：告警日志本身就存在被破坏的库里，所以**落库这一步也会失败**。
        # 因此这里以**服务端日志**为准：写入线程的降级行 + 告警消息。
        log_path = os.path.join(COPY_SERVER, "logs", "latest.log")
        degraded = ""
        warned = ""
        deadline = time.time() + 90
        while time.time() < deadline:
            time.sleep(5)
            try:
                with open(log_path, "r", encoding="utf-8", errors="replace") as handle:
                    log = handle.read()
            except OSError:
                continue
            degraded = next((line for line in log.splitlines() if "进入降级状态" in line), "")
            warned = next((line for line in log.splitlines() if "统计数据无法提交" in line), "")
            if degraded and warned:
                break
        if degraded:
            ok = True
            print("PASS  写入线程进入降级状态")
            print("   " + degraded.strip()[:220])
        else:
            print("FAIL  没有看到降级行")
        if warned:
            ok = True
            print("PASS  save_failure 告警已发出")
            print("   " + warned.strip()[:260])
        else:
            print("FAIL  没有看到 save_failure 告警消息")
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
        try:
            lock.close()
        except (NameError, sqlite3.Error):
            pass
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
