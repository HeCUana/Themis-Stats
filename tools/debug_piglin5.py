#!/usr/bin/env python3
"""诊断 4：把「tp + look」和「use once」拆成两批，看猪灵收不收金锭。

**关键时序**：Carpet 的一次性动作（`use once`）在**下一个 tick** 执行，
而控制台命令是在 tick **结束之后**才被批量执行的（`MinecraftServer#runAllTasks`）。
所以同一条指令链里 `tp`／`look` 还没生效，右键就已经按旧朝向打出去了，
而且这一次性动作被消耗掉，后面再发也没用。

正确做法：先发一批 `tp` + `look`，等命令真正落地，再单独发 `use once`。

用法：python tools/debug_piglin5.py
"""

from __future__ import annotations

import os
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
        time.sleep(1.0)
        pos = s.pos_of(BOT)
        if not pos:
            print("读不到假人坐标")
            return 1
        pos = s.prepare_arena(pos)
        s.run("kill @e[type=minecraft:item]")
        time.sleep(0.5)
        s.run("summon minecraft:piglin %.2f %.2f %.2f {Silent:1b,IsImmuneToZombification:1b}"
              % (pos[0] + 2.0, pos[1], pos[2]))
        time.sleep(1.5)
        print("猪灵副手（交互前）：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] equipment.offhand", settle=0.8)))

        # 第一批：把猪灵放到面前并转向（命令在 tick 结束后落地）
        s.run_batch([
            "tp @e[type=minecraft:piglin,limit=1] %.2f %.2f %.2f" % (pos[0] + 2.0, pos[1], pos[2]),
            "player %s look east" % BOT,
        ])
        time.sleep(0.35)
        # 第二批：右键（动作在下一个 tick 执行，这时朝向和位置都已经生效）
        s.run_batch(["player %s use once" % BOT])
        time.sleep(1.2)
        print("猪灵副手（交互后）：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] equipment.offhand", settle=0.8)))
        print("猪灵位置：", last_line(s.run(
            "data get entity @e[type=minecraft:piglin,limit=1] Pos", settle=0.8)))
        print("假人朝向：", last_line(s.run("data get entity %s Rotation" % BOT, settle=0.8)))
    finally:
        s.server.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
