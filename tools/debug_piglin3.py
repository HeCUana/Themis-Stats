#!/usr/bin/env python3
"""诊断 2：猪灵到底为什么不肯收金锭。

探针：
  ① 假人手里到底有没有金锭（读玩家的 Inventory / SelectedItem，不读 equipment）
  ② 新召唤的猪灵血量是不是满的（掉血＝被打过；被打过就会打开 ADMIRING_DISABLED）
  ③ 右键之后猪灵的副手
  ④ 猪灵 Brain 里的记忆（ADMIRING_DISABLED / ADMIRING_ITEM）

用法：python tools/debug_piglin3.py
"""

from __future__ import annotations

import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from e2e_test import BOT, Session  # noqa: E402

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


def last_line(text: str) -> str:
    lines = [line for line in text.strip().splitlines() if line.strip()]
    return lines[-1] if lines else "(空)"


def main() -> int:
    s = Session()
    s.start()
    try:
        s.clean_mobs()
        if not s.ensure_bot_alive():
            print("假人没进服，放弃")
            return 1
        s.run("execute as %s run gamemode creative" % BOT)
        s.run("execute as %s run item replace entity @s weapon.mainhand with minecraft:gold_ingot 2" % BOT)
        time.sleep(1.5)
        print("① 假人 SelectedItem：", last_line(s.run("data get entity %s SelectedItem" % BOT, settle=0.8)))
        print("① 假人 Inventory：", last_line(s.run("data get entity %s Inventory" % BOT, settle=0.8)))

        pos = s.pos_of(BOT)
        if not pos:
            print("读不到假人坐标")
            return 1
        pos = s.prepare_arena(pos)
        print("平整后坐标：", pos)

        s.run("summon minecraft:piglin %.2f %.2f %.2f {Silent:1b,IsImmuneToZombification:1b}"
              % (pos[0] + 2.0, pos[1], pos[2]))
        time.sleep(1.5)
        print("② 猪灵血量（刚召唤）：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] Health", settle=0.8)))

        # 第一次：主手拿金锭
        s.run_batch([
            "tp @e[type=minecraft:piglin,limit=1] %.2f %.2f %.2f" % (pos[0] + 2.0, pos[1], pos[2]),
            "player %s look east" % BOT,
            "player %s use once" % BOT,
        ])
        time.sleep(1.5)
        print("③ 猪灵副手（主手金锭）：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] equipment.offhand", settle=0.8)))
        print("② 猪灵血量（右键后）：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] Health", settle=0.8)))
        print("④ 猪灵 Brain：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] Brain", settle=0.8)))
        print("④ 假人 Inventory（交互后）：", last_line(s.run(
            "data get entity %s Inventory" % BOT, settle=0.8)))

        # 第二次：把金锭换到副手（Carpet 的 use 会依次尝试主手和副手）
        s.run("execute as %s run item replace entity @s weapon.offhand with minecraft:gold_ingot 2" % BOT)
        time.sleep(1.5)
        print("① 假人 Inventory（换副手后）：", last_line(s.run(
            "data get entity %s Inventory" % BOT, settle=0.8)))
        s.run_batch([
            "tp @e[type=minecraft:piglin,limit=1] %.2f %.2f %.2f" % (pos[0] + 2.0, pos[1], pos[2]),
            "player %s look east" % BOT,
            "player %s use once" % BOT,
        ])
        time.sleep(1.5)
        print("③ 猪灵副手（副手金锭）：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] equipment.offhand", settle=0.8)))
        print("④ 猪灵 Brain：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] Brain", settle=0.8)))
    finally:
        s.server.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
