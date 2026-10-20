#!/usr/bin/env python3
"""单独调试「猪灵交换」这一条：把每一步的状态都打出来。

为什么要有它：`piglin_barters` 的计数点在 `PiglinAi#throwItemsTowardPlayer`，
中间隔着「假人右键命中猪灵」「猪灵接过金锭」「猪灵欣赏满 119 刻」「猪灵把物品丢给
看得见的玩家」四步。任何一个环节没发生，这条指标都是 0。这个脚本逐步打印证据。

用法：python tools/debug_piglin.py
"""

from __future__ import annotations

import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from console_server import ConsoleServer  # noqa: E402

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

BOT = "bot_a"
PIGLIN = "@e[type=minecraft:piglin,limit=1,sort=nearest]"


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "creative"
    server = ConsoleServer()
    server.start()

    def run(command: str, settle: float = 1.5) -> str:
        server.send(command)
        time.sleep(settle)
        text = server.drain()
        print("> " + command)
        for line in text.strip().splitlines():
            print("    " + line)
        return text

    def pos_of(target: str):
        text = run("data get entity %s Pos" % target)
        found = re.findall(r"\[(-?[\d.]+)[d]?,\s*(-?[\d.]+)[d]?,\s*(-?[\d.]+)[d]?]", text)
        return [float(v) for v in found[-1]] if found else None

    try:
        print("=== 1. 假人上线 ===")
        run("player %s spawn" % BOT)
        deadline = time.time() + 240
        online = False
        while time.time() < deadline:
            time.sleep(5)
            text = run("list")
            if BOT in text:
                online = True
                break
        if not online:
            print("假人没进服，结束")
            return 1

        print("=== 2. 准备（模式 %s）===" % mode)
        run("execute as %s run gamemode %s" % (BOT, mode))
        if mode == "survival":
            # 生存模式会被猪灵攻击，戴金头盔才不会被攻击（物品 id 是 golden_helmet）。
            run("execute as %s run item replace entity @s armor.head with minecraft:golden_helmet" % BOT)
        run("execute as %s run item replace entity @s weapon.mainhand with minecraft:gold_ingot 2" % BOT)
        run("kill %s" % PIGLIN)
        time.sleep(1)
        bot_pos = pos_of(BOT)
        if not bot_pos:
            print("读不到假人坐标")
            return 1
        print("    假人坐标 %s" % bot_pos)
        run("summon minecraft:piglin %.2f %.2f %.2f {Silent:1b,IsImmuneToZombification:1b}"
            % (bot_pos[0] + 1.0, bot_pos[1], bot_pos[2]))
        time.sleep(2)
        run("tp %s %.2f %.2f %.2f" % (PIGLIN, bot_pos[0] + 1.0, bot_pos[1], bot_pos[2]))
        time.sleep(1)
        run("data get entity %s Pos" % PIGLIN)
        run("data get entity %s equipment.offhand" % PIGLIN)
        run("data get entity %s IsBaby" % PIGLIN)

        print("=== 3. 看向猪灵并右键 ===")
        pig_pos = pos_of(PIGLIN)
        if pig_pos:
            run("player %s look at %.2f %.2f %.2f" % (BOT, pig_pos[0], pig_pos[1] + 0.9, pig_pos[2]))
            time.sleep(1)
            run("player %s use once" % BOT)
        time.sleep(2)
        print("--- 右键后 2 秒：猪灵的副手有没有金锭（说明交互成功）---")
        run("data get entity %s equipment.offhand" % PIGLIN)

        print("=== 4. 等猪灵欣赏完（119 刻 ≈ 6 秒）===")
        time.sleep(12)
        print("--- 12 秒后：副手应该空了（物品已经丢给玩家）---")
        run("data get entity %s equipment.offhand" % PIGLIN)
        print("--- 假人有没有拿到猪灵丢回来的东西 ---")
        run("data get entity %s Inventory" % BOT)
        print("--- 猪灵的大脑记忆（找 admiring / wanted_item / nearest_visible_player）---")
        text = run("data get entity %s Brain" % PIGLIN, settle=2.0)
        for line in text.splitlines():
            low = line.lower()
            if "admiring" in low or "wanted" in low or "nearest_visible_player" in low:
                print("    " + line.strip()[:200])

        print("=== 5. 统计库里的 piglin_barters ===")
        run("themisstats fine piglin_barters bot")
        return 0
    finally:
        server.stop()


if __name__ == "__main__":
    raise SystemExit(main())
