#!/usr/bin/env python3
"""诊断 3：猪灵是不是已经在「欣赏」状态，所以拒绝再收金锭。

`PiglinAi.canAdmire` 要求：没被打过（ADMIRING_DISABLED）、没在欣赏（ADMIRING_ITEM）、
成年、手里是金锭。猪灵**会自己捡地上的金锭并开始欣赏**，一旦进入欣赏状态，
后面再怎么右键它都不收。

探针：
  ① 地面上的掉落物（`@e[type=minecraft:item]`）
  ② 猪灵的 Pose（手臂姿态：neutral / admiring_item）
  ③ 猪灵的副手
  ④ 假人主手的金锭数量（生存模式下才会真扣）

用法：python tools/debug_piglin4.py
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
        # 生存模式：创造模式下被消耗的物品会被原版补回去，看不出有没有真的收到金锭
        s.run("execute as %s run gamemode survival" % BOT)
        s.run("execute as %s run item replace entity @s weapon.mainhand with minecraft:gold_ingot 2" % BOT)
        time.sleep(1.5)
        print("① 地面掉落物：", last_line(s.run(
            "data get entity @e[type=minecraft:item,limit=3] id", settle=0.8)))
        print("④ 假人 Inventory（交互前）：", last_line(s.run(
            "data get entity %s Inventory" % BOT, settle=0.8)))

        pos = s.pos_of(BOT)
        if not pos:
            print("读不到假人坐标")
            return 1
        pos = s.prepare_arena(pos)
        # 清掉周围的掉落物：猪灵会捡金锭，捡到就开始欣赏，之后再也不收金锭
        s.run("kill @e[type=minecraft:item]")
        time.sleep(0.5)
        print("① 地面掉落物（清空后）：", last_line(s.run(
            "data get entity @e[type=minecraft:item,limit=3] id", settle=0.8)))

        s.run("summon minecraft:piglin %.2f %.2f %.2f {Silent:1b,IsImmuneToZombification:1b}"
              % (pos[0] + 2.0, pos[1], pos[2]))
        time.sleep(1.5)
        print("② 猪灵 Pose（交互前）：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] Pose", settle=0.8)))

        s.run_batch([
            "tp @e[type=minecraft:piglin,limit=1] %.2f %.2f %.2f" % (pos[0] + 2.0, pos[1], pos[2]),
            "player %s look east" % BOT,
            "player %s use once" % BOT,
        ])
        time.sleep(1.5)
        print("② 猪灵 Pose（交互后）：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] Pose", settle=0.8)))
        print("③ 猪灵副手：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] equipment.offhand", settle=0.8)))
        print("④ 假人 Inventory（交互后）：", last_line(s.run(
            "data get entity %s Inventory" % BOT, settle=0.8)))
        print("③ 猪灵位置：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] Pos", settle=0.8)))
        print("① 地面掉落物（交互后）：", last_line(s.run(
            "data get entity @e[type=minecraft:item,limit=3] id", settle=0.8)))
    finally:
        s.server.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
