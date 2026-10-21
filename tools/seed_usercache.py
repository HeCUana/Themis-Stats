#!/usr/bin/env python3
"""把假人名字预置进服务端的 usercache.json，避免召唤时联网查 Mojang 档案。

为什么需要它
------------
Carpet 召唤假人时会走原版的「名字 → 档案」解析：

    /player <名字> spawn
      → carpet.commands.PlayerCommand.spawn
      → net.minecraft.server.players.OldUsersConverter.convertMobOwnerIfNecessary
      → net.minecraft.server.players.CachedUserNameToIdResolver.get
      → CachedUserNameToIdResolver.lookupGameProfile   ← 阻塞式 HTTPS 调用 Mojang

名字**不在**服务端的 usercache.json 里时，这次查询会：
  * 在服务端主线程上同步等待（实测约 1 秒一次，召唤路径上会做两次）；
  * 打出 `Couldn't find profile with name: X` 与 `Could not lookup user whitelist entry for X` 的 WARN；
  * 让服务端出现 `Can't keep up!` 的卡顿告警。

这不是 Themis Stats 的问题（调用栈里没有本 Mod），1.21.11 与 26.3 的实现完全同构；
但它会让「召唤一个没进过服的名字」变成一次会卡住服务端的事。

把名字预先写进 usercache.json 之后，解析直接命中本地缓存：
不联网、不报警、不卡顿。离线 UUID 的算法是
    UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))
（与原版 `UUIDUtil.createOfflinePlayerUUID` 一致）。

用法
----
    python tools/seed_usercache.py server-263 bot_a Bot_B
    python tools/seed_usercache.py server-263 --list
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import uuid as uuidlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def offline_uuid(name: str) -> str:
    """The offline-mode UUID of a player name (same as vanilla)."""
    digest = hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest()
    return str(uuidlib.UUID(bytes=digest))


def cache_path(server_dir: str) -> str:
    return os.path.join(server_dir, "usercache.json")


def read_cache(path: str) -> list:
    if not os.path.isfile(path):
        return []
    with open(path, "r", encoding="utf-8") as handle:
        try:
            data = json.load(handle)
        except ValueError:
            return []
    return data if isinstance(data, list) else []


def write_cache(path: str, entries: list) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(entries, handle, ensure_ascii=False, indent=1)


def main(argv: list[str]) -> int:
    args = [a for a in argv if a != "--list"]
    server_dir = args[0] if args else os.path.join(ROOT, "server")
    path = cache_path(server_dir)
    entries = read_cache(path)

    if "--list" in argv:
        print("档案缓存：%s" % path)
        for entry in entries:
            print("  %-20s %s" % (entry.get("name"), entry.get("uuid")))
        return 0

    names = args[1:]
    if not names:
        print(__doc__)
        return 2

    known = {str(e.get("name", "")).lower(): e for e in entries}
    added = 0
    for name in names:
        if name.lower() in known:
            print("已存在：%s" % name)
            continue
        entries.append({"uuid": offline_uuid(name), "name": name,
                        "expiresOn": "2026-12-31 00:00:00 +0800"})
        known[name.lower()] = entries[-1]
        added += 1
        print("写入：%s -> %s" % (name, offline_uuid(name)))

    write_cache(path, entries)
    print("档案缓存：%s（新增 %d 条，共 %d 条）" % (path, added, len(entries)))
    print("提示：改完要**重启服务端**才会生效（档案缓存在启动时读入）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
