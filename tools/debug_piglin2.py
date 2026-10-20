#!/usr/bin/env python3
"""诊断：假人右键到底有没有命中猪灵。

把两件事分开验：
  ① 射线有没有命中实体 —— 用 Carpet 的 `player <名> attack`（和 use 走同一个
     `Tracer.rayTrace`），看猪灵掉不掉血；
  ② 命中之后猪灵的交互逻辑有没有把金锭收进副手 —— 看 `equipment.offhand`。

用法：python tools/debug_piglin2.py
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

        pos = s.pos_of(BOT)
        if not pos:
            print("读不到假人坐标")
            return 1
        print("假人坐标：", pos)
        pos = s.prepare_arena(pos)
        print("平整后坐标：", pos)

        s.run("summon minecraft:piglin %.2f %.2f %.2f {Silent:1b,IsImmuneToZombification:1b}"
              % (pos[0] + 2.0, pos[1], pos[2]))
        time.sleep(1.5)

        # ① 假人的朝向有没有真的改掉
        s.run("player %s look east" % BOT, settle=0.6)
        text = s.run("data get entity %s Rotation" % BOT, settle=0.8)
        print("look east 之后的朝向：", last_line(text))

        # ② 主手到底有没有金锭
        text = s.run("data get entity %s equipment.mainhand" % BOT, settle=0.8)
        print("主手物品：", last_line(text))

        # ③ 射线到底命中没有 —— 用 attack 试一次（和 use 走同一个 Tracer.rayTrace）
        text = s.run("data get entity @e[type=minecraft:piglin,limit=1] Health", settle=0.8)
        print("猪灵血量（攻击前）：", last_line(text))
        s.run_batch([
            "tp @e[type=minecraft:piglin,limit=1] %.2f %.2f %.2f" % (pos[0] + 2.0, pos[1], pos[2]),
            "player %s look east" % BOT,
            "player %s attack" % BOT,
        ])
        time.sleep(1.5)
        text = s.run("data get entity @e[type=minecraft:piglin,limit=1] Health", settle=0.8)
        print("猪灵血量（攻击后）：", last_line(text))
        print("→ 血量掉了就说明射线命中了实体；没掉就说明射线根本没打到猪灵")

        # ④ 命中之后，use 能不能把金锭收进副手
        s.run_batch([
            "tp @e[type=minecraft:piglin,limit=1] %.2f %.2f %.2f" % (pos[0] + 2.0, pos[1], pos[2]),
            "player %s look east" % BOT,
            "player %s use once" % BOT,
        ])
        time.sleep(1.5)
        text = s.run("data get entity @e[type=minecraft:piglin,limit=1] equipment.offhand", settle=0.8)
        print("猪灵副手：", last_line(text))

        # ⑤ 假人自己的主手还有没有金锭（被收走就说明交互真的发生了）
        text = s.run("data get entity %s equipment.mainhand" % BOT, settle=0.8)
        print("假人主手（交互后）：", last_line(text))
    finally:
        s.server.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
