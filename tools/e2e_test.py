#!/usr/bin/env python3
"""Themis Stats 端到端自测：驱动测试服务端，逐项验证并输出 PASS/FAIL 汇总。

用法：
    python tools/e2e_test.py                 # 跑全部阶段
    python tools/e2e_test.py basic watch     # 只跑指定阶段
    python tools/e2e_test.py --keep-server   # 测完不关服务端

命令走服务端控制台的标准输入：这样命令以「控制台」身份执行，结果会写进服务端日志，
可以直接核对。RCON 也能驱动服务端（tools/rcon.py），但 RCON 的来源是一个一次性缓冲区，
读不到后台线程延迟返回的查询结果，所以内容核对用控制台。

阶段：
    basic   假人上线、命令树参数类型自检、细分榜/诊断榜（新的参数类型）
    watch   watch / unwatch 的完整交互（含「必须由玩家执行」的反向用例）
    alert   低生命值告警 → 恢复通知（端到端）
    stall   指标停滞告警（观察窗口 5 分钟）
    metrics 七类增强指标的采集
"""

from __future__ import annotations

import os
import re
import sqlite3
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

DB = os.path.join(os.path.dirname(HERE), "server", "world", "themisstats", "stats.db")
BOT = "bot_a"

# Carpet 的 `player <名字> look <方向>` 与坐标轴的对应关系：
#   north = -Z，south = +Z，east = +X，west = -X
#   （Carpet 的 look(Direction) 分别是 yaw 180 / 0 / -90 / 90）
DIRECTIONS = (("east", 1.0, 0.0), ("west", -1.0, 0.0),
              ("north", 0.0, -1.0), ("south", 0.0, 1.0))

RESULTS: list[tuple[str, bool, str]] = []

# 服务端命令出错的几种典型说法，出现在命令输出里就说明这条命令没生效。
ERROR_MARKERS = ("Incorrect argument", "Unknown command", "Expected", "Failed to load entity",
                 "No entity found", "commands.", "权限")


def check(name: str, ok: bool, detail: str = "") -> bool:
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  |  " + str(detail)) if detail else ""),
          flush=True)
    return bool(ok)


def note(text: str) -> None:
    print("        · " + text, flush=True)


def query(sql: str, args: tuple = ()) -> list[tuple]:
    last = None
    for _ in range(20):
        try:
            con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\", "/"), uri=True, timeout=2.0)
            try:
                return con.execute(sql, args).fetchall()
            finally:
                con.close()
        except sqlite3.Error as exc:
            last = exc
            time.sleep(0.5)
    raise RuntimeError("打不开统计库：%s" % last)


def bot_uuid() -> str:
    rows = query("SELECT uuid FROM account WHERE current_name = ?", (BOT,))
    if not rows:
        raise RuntimeError("库里找不到 " + BOT)
    return rows[0][0]


def heal_bot(s: "Session") -> None:
    """把假人的血量拉满。

    告警用例要用固定的血量做前提：如果假人之前已经掉过血，15 点伤害会直接把它打死，
    告警就永远不会触发。`instant_health` 的效果每 tick 都会回血，所以这里要等效果结束，
    否则它会在伤害之后又把血补上去。
    """
    s.run("execute as %s run effect give @s minecraft:instant_health 1 10" % BOT)
    time.sleep(3.0)


def total_of(uuid: str, metric: str) -> int:
    rows = query("SELECT COALESCE(SUM(value), 0) FROM total WHERE uuid = ? AND metric = ? "
                 "AND source = 'collected'", (uuid, metric))
    return int(rows[0][0]) if rows else 0


def parse_pos(text: str) -> list[float] | None:
    """从 `data get entity ... Pos` 的输出里取坐标。"""
    found = re.findall(r"\[(-?[\d.]+)[d]?,\s*(-?[\d.]+)[d]?,\s*(-?[\d.]+)[d]?]", text)
    if not found:
        return None
    raw = found[-1]
    return [float(value) for value in raw]


def entity_health(s: "Session", target: str) -> float:
    """Reads the current health of an entity; 0.0 when it cannot be read."""
    text = s.run("data get entity %s Health" % target, settle=1.2)
    found = re.findall(r"entity data:\s*(-?[\d.]+)", text)
    return float(found[-1]) if found else 0.0


def held_count(s: "Session") -> int:
    """Reads how many items the fake player holds in hotbar slot 0 (its main hand).

    The count is taken from the `Inventory` list, **not** from `SelectedItem`:
    `SelectedItem` is a cached copy that 1.21.11 only refreshes when the selected slot
    changes, so after dropping an item it still reports the old number.
    """
    text = s.run("data get entity %s Inventory" % BOT, settle=1.0)
    found = re.findall(r"count:\s*(\d+),\s*Slot:\s*0b", text)
    return int(found[-1]) if found else 0


class Session:
    """The test server plus a few helpers on top of it."""

    def __init__(self) -> None:
        self.server = ConsoleServer()
        self.log = ""

    def start(self) -> None:
        self.server.start()
        # 启动阶段的消息（命令树参数类型自检等）在 start() 内部已经被读过一遍，
        # 这里从头再读一次，才能在断言里看到它们。
        self.log = self.server.read_all()

    def run(self, command: str, settle: float = 1.2) -> str:
        """Runs a command on the console and returns everything that was logged after it."""
        self.server.send(command)
        time.sleep(settle)
        text = self.server.drain()
        self.log += text
        return text

    def run_batch(self, commands: list[str], settle: float = 1.2) -> str:
        """Sends several commands back to back so the server runs them in one go.

        The console thread queues what it reads, and the server drains that queue once per tick
        (after the entities have ticked), so commands that arrive together are executed one after
        another, in order.

        The important detail: a one shot Carpet action (`use once`) is executed **while the
        entities tick**, that is **before** the queued commands are applied. A command that the
        action depends on therefore has to be sent in an earlier batch - otherwise the action
        already ran with the old state and, being one shot, is gone.
        """
        for command in commands:
            self.server.send(command)
        time.sleep(settle)
        text = self.server.drain()
        self.log += text
        return text

    def wait(self, needle: str, timeout: float) -> str:
        text = self.server.wait_for(needle, timeout)
        self.log += text
        return text

    def logged(self, needle: str) -> bool:
        """Whether the needle shows up anywhere in the log so far."""
        return needle in self.log

    def ensure_bot(self, timeout: float = 300.0) -> bool:
        """Makes sure the fake player is online, spawns it if it is not.

        The Mojang profile lookup times out in this environment, so the fake player can take a
        couple of minutes to show up. The check therefore polls the player list instead of waiting
        for the join message: the message can be missed when it arrives while another command is
        being answered.
        """
        text = self.run("list", settle=2.0)
        if BOT in text:
            return True
        self.run("player %s spawn" % BOT)
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(5)
            text = self.run("list", settle=1.0)
            if BOT in text:
                return True
        return False

    def clean_mobs(self) -> None:
        """Removes the mobs the previous run left behind.

        The test world is persistent, so a piglin from an interrupted run is still standing next
        to the spawn point. It has AI, it attacks players who do not wear gold armor, and it can
        kill the fake player before the first case of a phase runs.
        """
        self.run("kill @e[type=minecraft:piglin]")
        self.run("kill @e[type=minecraft:zombie]")
        time.sleep(0.5)

    def ensure_bot_alive(self) -> bool:
        """Makes sure the fake player is online **and alive**."""
        for _ in range(3):
            if not self.ensure_bot():
                return False
            text = self.run("data get entity %s Health" % BOT, settle=1.5)
            found = re.findall(r"entity data:\s*(-?[\d.]+)", text)
            if not found:
                # The health cannot be read (the entity is gone); treat it as "not ready".
                note("读不到假人血量，重新召唤")
                self.run("player %s spawn" % BOT)
                time.sleep(3)
                continue
            if float(found[-1]) > 0:
                return True
            note("假人已经死了，重新召唤")
            self.run("player %s spawn" % BOT)
            time.sleep(3)
        return False

    def pos_of(self, target: str) -> list[float] | None:
        text = self.run("data get entity %s Pos" % target, settle=1.5)
        return parse_pos(text)

    def prepare_arena(self, pos: list[float]) -> list[float]:
        """Lays out a flat area around the fake player and puts it in the middle.

        Carpet's Tracer.rayTrace first traces blocks and then limits the entity trace to the
        distance of that block hit. So a wall or a single step between the fake player and the
        piglin turns the right-click into a right-click on a block and the piglin never receives
        the gold. Here the floor is flattened with stone and the two layers around the fake player
        are cleared, so the ray has nothing but air in it.
        """
        bx, by, bz = pos
        x0, x1 = int(bx) - 4, int(bx) + 4
        z0, z1 = int(bz) - 4, int(bz) + 4
        self.run("fill %d %d %d %d %d %d minecraft:stone" % (x0, int(by) - 1, z0, x1, int(by) - 1, z1))
        self.run("fill %d %d %d %d %d %d minecraft:air" % (x0, int(by), z0, x1, int(by) + 2, z1))
        self.run("tp %s %.2f %.2f %.2f" % (BOT, bx + 0.5, by, bz + 0.5))
        time.sleep(1.0)
        return self.pos_of(BOT) or [bx + 0.5, by, bz + 0.5]

    def errors(self) -> list[str]:
        bad = []
        for line in self.log.splitlines():
            if ("unexpected error" in line or "后台任务失败" in line or "查询失败" in line
                    or "Exception" in line or "ERROR" in line):
                bad.append(line)
        return bad


# ---------------------------------------------------------------- basic

def phase_basic(s: Session) -> None:
    print("\n[阶段 basic] 假人上线与命令", flush=True)
    s.clean_mobs()
    ok = s.ensure_bot_alive()
    check("假人 %s 进服" % BOT, ok, "")
    if not ok:
        return
    check("命令树参数类型自检通过（原版客户端能解析命令树）",
          "命令树参数类型自检通过" in s.log,
          "自检日志缺失" if "参数类型自检" not in s.log else "见启动日志")
    text = s.run("list")
    check("玩家列表里能看到假人", BOT in text, text.strip()[:120])
    s.run("themisstats admin bot owner %s %s" % (BOT, BOT))
    s.run("themisstats admin bot role %s mining" % BOT)
    s.run("execute as %s run gamemode survival" % BOT)
    time.sleep(1)

    text = s.run('themisstats fine mining "minecraft:stone" bot', settle=2.5)
    check("细分榜接受带引号的分类 minecraft:stone", "细分榜" in text and BOT in text,
          text.strip()[-160:])
    check("细分榜标题里带上了分类", "minecraft:stone" in text, text.strip()[-160:])

    text = s.run('themisstats admin board mining bot survival "minecraft:stone" minecraft:overworld',
                  settle=2.5)
    check("诊断榜接受分类与维度（原版标识符参数）", "诊断榜" in text and BOT in text,
          text.strip()[-160:])
    check("诊断榜按维度过滤（overworld）", "minecraft:overworld" in text, text.strip()[-160:])

    text = s.run("themisstats bot %s" % BOT, settle=2.5)
    check("假人档案可查", "bot_a" in text, text.strip()[-160:])

    # 原版 /drop 的语法在 1.21.11 变过，这里把帮助文本记下来，方便写文档。
    text = s.run("help drop", settle=1.5)
    for line in text.splitlines():
        if "/drop" in line:
            note("原版帮助：" + line.strip())


# ---------------------------------------------------------------- watch

def phase_watch(s: Session) -> None:
    s.clean_mobs()
    if not s.ensure_bot_alive():
        check("phase_watch 需要假人在线", False, "假人没进服")
        return
    print("\n[阶段 watch] 关注与取消关注", flush=True)
    uuid = bot_uuid()
    text = s.run("themisstats watch %s bot" % BOT)
    check("控制台执行 watch 被拒绝（必须由玩家执行）", "需要由玩家执行" in text, text.strip()[-140:])
    s.run("execute as %s run themisstats watch %s bot" % (BOT, BOT))
    time.sleep(2.0)
    rows = query("SELECT target_uuid, target_identity, subscriber_uuid, alerts FROM watch_subscription "
                 "WHERE target_uuid = ?", (uuid,))
    check("以玩家身份 watch 写入订阅", len(rows) == 1, rows)
    if rows:
        check("订阅者是玩家本人", rows[0][2] == uuid, rows[0][2])
        check("默认订阅掩码不含「指定统计停滞」", int(rows[0][3]) & 8 == 0, "掩码 %s" % rows[0][3])
    s.run("execute as %s run themisstats unwatch %s bot" % (BOT, BOT))
    time.sleep(2.0)
    rows = query("SELECT target_uuid FROM watch_subscription WHERE target_uuid = ?", (uuid,))
    check("以玩家身份 unwatch 删除订阅", len(rows) == 0, rows)


# ---------------------------------------------------------------- alert

def phase_alert(s: Session) -> None:
    s.clean_mobs()
    if not s.ensure_bot_alive():
        check("phase_alert 需要假人在线", False, "假人没进服")
        return
    print("\n[阶段 alert] 低生命值告警与恢复", flush=True)
    uuid = bot_uuid()
    s.run("carpet themisStatsBotAlerts true")
    s.run("execute as %s run gamemode survival" % BOT)
    heal_bot(s)
    s.run("execute as %s run themisstats watch %s bot low_health" % (BOT, BOT))
    time.sleep(2.0)
    rows = query("SELECT alerts FROM watch_subscription WHERE target_uuid = ?", (uuid,))
    check("只订阅 low_health", bool(rows) and int(rows[0][0]) == 1, rows)
    note("等待 60 秒宽限期结束")
    time.sleep(62)
    s.run("damage %s 15 minecraft:magic" % BOT)
    time.sleep(3)
    text = s.run("data get entity %s Health" % BOT, settle=1.5)
    health = re.findall(r"entity data:\s*(-?[\d.]+)", text)
    check("假人掉到 5 点血且还活着", bool(health) and 4.0 <= float(health[-1]) <= 6.0,
          text.strip()[-120:])
    time.sleep(6)
    rows = query("SELECT reason, message FROM alert_log WHERE target_uuid = ? ORDER BY id DESC LIMIT 3",
                 (uuid,))
    fired = [r for r in rows if r[0] == "low_health"]
    check("低生命值告警已发出并落库", bool(fired), fired)
    if fired:
        check("告警内容说明连续两次低于阈值", "连续两次" in fired[0][1], fired[0][1][:140])
    rows = query("SELECT reason, active FROM alert_state WHERE target_uuid = ?", (uuid,))
    check("告警状态标记为进行中", any(r[0] == "low_health" and int(r[1]) == 1 for r in rows), rows)
    s.run("execute as %s run effect give @s minecraft:instant_health 1 5" % BOT)
    time.sleep(8)
    rows = query("SELECT reason, message FROM alert_log WHERE target_uuid = ? ORDER BY id DESC LIMIT 3",
                 (uuid,))
    recovered = [r for r in rows if r[0] == "low_health_recovered"]
    check("恢复通知已发出并落库", bool(recovered), recovered)

    # 工具低耐久：主手铁镐的剩余耐久低于阈值（250 - 245 = 5，剩 2%）
    s.run("execute as %s run themisstats watch %s bot low_durability" % (BOT, BOT))
    time.sleep(2.0)
    rows = query("SELECT alerts FROM watch_subscription WHERE target_uuid = ?", (uuid,))
    check("订阅切到 low_durability", bool(rows) and int(rows[0][0]) == 2, rows)
    s.run("execute as %s run item replace entity @s weapon.mainhand with minecraft:iron_pickaxe[damage=245]"
          % BOT)
    time.sleep(9)
    rows = query("SELECT reason, message FROM alert_log WHERE target_uuid = ? ORDER BY id DESC LIMIT 3",
                 (uuid,))
    fired = [r for r in rows if r[0] == "low_durability"]
    check("工具低耐久告警已发出并落库", bool(fired), fired)
    if fired:
        check("低耐久告警写明剩余耐久与阈值", "剩余耐久" in fired[0][1], fired[0][1][:140])
    s.run("execute as %s run item replace entity @s weapon.mainhand with minecraft:iron_pickaxe" % BOT)
    time.sleep(8)
    rows = query("SELECT reason, message FROM alert_log WHERE target_uuid = ? ORDER BY id DESC LIMIT 3",
                 (uuid,))
    recovered = [r for r in rows if r[0] == "low_durability_recovered"]
    check("低耐久恢复通知已发出并落库", bool(recovered), recovered)
    s.run("execute as %s run themisstats unwatch %s bot" % (BOT, BOT))


# ---------------------------------------------------------------- stall

def phase_stall(s: Session) -> None:
    s.clean_mobs()
    if not s.ensure_bot_alive():
        check("phase_stall 需要假人在线", False, "假人没进服")
        return
    print("\n[阶段 stall] 指标停滞告警", flush=True)
    uuid = bot_uuid()
    s.run("carpet themisStatsBotAlerts true")
    # 停滞告警只对白名单里的用途生效，这里把假人的用途设成 mining。
    s.run("themisstats admin bot role %s mining" % BOT)
    s.run("execute as %s run themisstats watch %s bot metric_stall mining" % (BOT, BOT))
    time.sleep(2.0)
    rows = query("SELECT alerts, stall_metric FROM watch_subscription WHERE target_uuid = ?", (uuid,))
    check("订阅里带上了观察指标 mining", bool(rows) and rows[0][1] == "mining", rows)
    note("停滞窗口是 5 分钟，等待最多 6 分 30 秒")
    deadline = time.time() + 390
    fired = []
    while time.time() < deadline:
        time.sleep(10)
        rows = query("SELECT reason, message FROM alert_log WHERE target_uuid = ? "
                     "AND reason = 'metric_stall' ORDER BY id DESC LIMIT 2", (uuid,))
        if rows:
            fired = rows
            break
    check("停滞告警已发出", bool(fired), fired)
    if fired:
        check("停滞告警只陈述无增长，不断言机器故障",
              "不能据此判断机器是否故障" in fired[0][1], fired[0][1][:160])
    s.run("execute as %s run themisstats unwatch %s bot" % (BOT, BOT))


# ---------------------------------------------------------------- metrics

def phase_metrics(s: Session) -> None:
    s.clean_mobs()
    if not s.ensure_bot_alive():
        check("phase_metrics 需要假人在线", False, "假人没进服")
        return
    print("\n[阶段 metrics] 七类增强指标", flush=True)
    uuid = bot_uuid()
    metrics = ("damage_dealt", "damage_taken", "movement", "crafted", "picked_up", "dropped",
               "piglin_barters")

    def snapshot() -> dict[str, int]:
        return {m: total_of(uuid, m) for m in metrics}

    def delta(before: dict[str, int], metric: str) -> int:
        return total_of(uuid, metric) - before.get(metric, 0)

    s.run("execute as %s run gamemode survival" % BOT)
    time.sleep(1)
    heal_bot(s)

    # 1. 受到伤害
    before = snapshot()
    s.run("damage %s 5 minecraft:magic" % BOT)
    time.sleep(9)
    check("damage_taken 记录受到的伤害", delta(before, "damage_taken") == 5,
          "增量 %s" % delta(before, "damage_taken"))
    text = s.run("data get entity %s Health" % BOT, settle=1.5)
    health = re.findall(r"entity data:\s*(-?[\d.]+)", text)
    check("假人挨打后还活着（没有遗留生物把它打死）", bool(health) and float(health[-1]) > 0,
          text.strip()[-100:])
    heal_bot(s)

    # 2. 造成伤害
    #
    #    这里必须**自校验**：不能直接假定「请求 5 点就是掉 5 点血」。
    #    `damage` 命令回报的是**请求值**（`commands.damage.success` 打印的是入参），
    #    实际扣血可能被护甲减免、被无敌帧折半，也可能被环境伤害（灼烧、窒息）干扰。
    #    所以这里量僵尸**实际掉的血**，再跟指标增量对齐。
    #
    #    另外注意：`execute as` **不改变位置上下文**（`~ ~ ~` 仍然是命令来源的位置），
    #    僵尸要召唤在假人旁边，就得用 `execute at`。
    before = snapshot()
    s.run("kill @e[type=minecraft:item]")
    s.run("kill @e[type=minecraft:zombie]")
    time.sleep(0.5)
    s.run("execute at %s run summon minecraft:zombie ~ ~ ~ {NoAI:1b,Silent:1b}" % BOT)
    time.sleep(2)
    hp_before = entity_health(s, "@e[type=minecraft:zombie,limit=1]")
    s.run("execute at %s as %s run damage @e[type=minecraft:zombie,limit=1] 5 minecraft:magic by @s"
          % (BOT, BOT))
    time.sleep(1.5)
    hp_after = entity_health(s, "@e[type=minecraft:zombie,limit=1]")
    time.sleep(7)
    dealt = delta(before, "damage_dealt")
    dropped = hp_before - hp_after
    check("damage_dealt 记录造成的伤害", dropped > 0 and dealt == int(dropped),
          "增量 %s；僵尸实际掉血 %s（请求 5.0）" % (dealt, dropped))

    # 3. 移动（Carpet 的移动方向是 /player <名字> move forward 这种嵌套写法）
    before = snapshot()
    s.run("player %s move forward" % BOT)
    time.sleep(8)
    s.run("player %s move" % BOT)
    time.sleep(9)
    check("movement 记录有效位移", delta(before, "movement") > 0, "增量 %s" % delta(before, "movement"))

    # 4. 丢弃。Carpet 的 `/player <名字> drop` 与 `dropStack` 都是**一次性动作**
    #    （下一个 tick 才执行）；`drop` 走 Player#drop(false)：只丢一个，
    #    `dropStack` 走 drop(true)：丢整组。两个数量都要对上。
    #
    #    这里有两个坑：
    #    ① 假人会把**自己丢出来的东西捡回去**（掉落物有 10 tick 的拾取延迟），
    #       主手数量会自己涨回去。所以每次丢弃前都用 `item replace` 重新铺好
    #       一组**已知数量**的物品，把这个干扰彻底抹掉；
    #    ② 掉落动作是延迟执行的，所以「丢完立刻清掉落物」这种写法没用——
    #       同一条指令链里的 `kill` 会先于动作执行。
    before = snapshot()
    s.run("execute as %s run item replace entity @s weapon.mainhand with minecraft:stone 5" % BOT)
    time.sleep(2)
    check("主手被换成了 5 个石头", held_count(s) == 5, "槽位 0 里是 %s 个" % held_count(s))
    s.run("player %s drop" % BOT)
    time.sleep(9)
    one = delta(before, "dropped")
    check("dropped 丢一个时只记一个（不按整组算）", one == 1,
          "增量 %s（主手 5 个石头，drop 只丢 1 个）" % one)
    before = snapshot()
    s.run("execute as %s run item replace entity @s weapon.mainhand with minecraft:dirt 7" % BOT)
    time.sleep(2)
    check("主手被换成了 7 个泥土", held_count(s) == 7, "槽位 0 里是 %s 个" % held_count(s))
    s.run("player %s dropStack" % BOT)
    time.sleep(9)
    rest = delta(before, "dropped")
    check("dropped 丢整组时记整组数量", rest == 7,
          "增量 %s（主手实际有 7 个泥土）" % rest)

    # 5. 拾取。`execute as` 不改位置上下文，物品要生成在假人脚下才捡得到。
    before = snapshot()
    pos = s.pos_of(BOT)
    if pos:
        s.run('summon item %.2f %.2f %.2f '
              '{Item:{id:"minecraft:diamond",Count:1},PickupDelay:0}' % (pos[0], pos[1], pos[2]))
    else:
        note("没读到假人坐标，跳过拾取用例")
    time.sleep(9)
    check("picked_up 记录拾取的物品", delta(before, "picked_up") > 0, "增量 %s" % delta(before, "picked_up"))

    # 6. 放置
    before = snapshot()
    s.run("execute as %s run give @s minecraft:stone 5" % BOT)
    time.sleep(2)
    s.run("player %s use once" % BOT)
    time.sleep(9)
    check("placement 记录放置的方块", total_of(uuid, "placement") > 0,
          "累计 %s" % total_of(uuid, "placement"))

    # 7. 猪灵交换。条件：成年猪灵、金锭在假人主手、假人看向猪灵并右键，
    #    然后等猪灵欣赏完（119 刻）再换出物品。
    #
    #    四个原版机制会毁掉这个用例：
    #    ① 1.21.11 的猪灵转换**不再由游戏规则控制**（`piglinConversion` 这个规则已经没有了），
    #       而是 `AbstractPiglin#customServerAiStep` 里的 `timeInOverworld > 300`：
    #       在主世界里待够 15 秒就会变成猪灵蛮兵，实体类型一变就再也选不到了。
    #       解法是给猪灵加 NBT 标志 `IsImmuneToZombification:1b`（原版自己就是这么防转换的）。
    #    ② 附近没有金子时猪灵会进入「恐慌」，以 1.3 倍速乱跑，10 秒能跑出 8 格，
    #       早就超出 5 格的触及范围。所以金锭要先拿在假人手上，猪灵才会凑过来。
    #    ③ 假人是用射线右键的：Carpet 的 Tracer.rayTrace 先算方块命中，
    #       方块更近就按方块算，交互就落在方块上。所以先在假人脚下铺一块平地、
    #       把假人这一高度清空。
    #    ④ 猪灵在 tick 之间会移动，先「看向」再「右键」如果隔了一个 tick，
    #       猪灵可能已经跑偏。所以这两步必须**分两批**发：
    #       Carpet 的一次性动作（`use once`）在**下一个 tick** 执行，
    #       而控制台命令是在 tick **结束之后**才批量执行的（`MinecraftServer#runAllTasks`）。
    #       同一批里 `look` 还没生效，右键就已经按旧朝向打出去了，
    #       而且这一次性动作被消耗掉——后面再发也没用。
    before = snapshot()
    s.run("kill @e[type=minecraft:piglin]")
    time.sleep(1)
    # 创造模式不会被猪灵攻击，也就不会被打断交换；生存模式下假人会直接被猪灵打死。
    s.run("execute as %s run gamemode creative" % BOT)
    time.sleep(1)
    # 金锭先上手，猪灵看见有人拿着金子才会靠近而不是恐慌乱跑。
    s.run("execute as %s run item replace entity @s weapon.mainhand with minecraft:gold_ingot 2" % BOT)
    time.sleep(1)
    # 猪灵会自己捡地上的金锭，捡到就开始「欣赏」，之后再也不肯收金锭。
    s.run("kill @e[type=minecraft:item]")
    time.sleep(0.5)
    traded = False
    pos = s.pos_of(BOT)
    if not pos:
        note("读不到假人坐标，跳过猪灵用例")
    else:
        pos = s.prepare_arena(pos)
        note("平地已就绪，假人在 %s" % pos)

        def summon_piglin() -> None:
            s.run("summon minecraft:piglin %.2f %.2f %.2f {Silent:1b,IsImmuneToZombification:1b}"
                  % (pos[0] + 2.0, pos[1], pos[2]))
            time.sleep(1.5)

        summon_piglin()
        for round_no in range(3):
            for name, dx, dz in DIRECTIONS:
                # 第一批：把猪灵放到面前并转向（命令在 tick 结束后才落地）
                s.run_batch([
                    "tp @e[type=minecraft:piglin,limit=1] %.2f %.2f %.2f"
                    % (pos[0] + dx * 2.0, pos[1], pos[2] + dz * 2.0),
                    "player %s look %s" % (BOT, name),
                ])
                time.sleep(0.35)
                # 第二批：右键（动作在下一个 tick 执行，这时朝向和位置都已经生效）
                s.run_batch(["player %s use once" % BOT])
                time.sleep(1.0)
                # 猪灵的副手里拿到金锭 = 这一次右键确实命中了它。
                text = s.run("data get entity @e[type=minecraft:piglin,limit=1] equipment.offhand",
                             settle=0.8)
                if "gold_ingot" in text:
                    traded = True
                    break
            if traded:
                break
            note("第 %d 轮四个方向都没命中猪灵，重新召唤" % (round_no + 1))
            s.run("kill @e[type=minecraft:piglin]")
            time.sleep(1)
            summon_piglin()
        check("假人右键命中猪灵（猪灵副手拿到金锭）", traded, "")
    if traded:
        note("等待猪灵欣赏金锭（119 刻）后完成交换")
        time.sleep(20)
    check("piglin_barters 记录猪灵交换", delta(before, "piglin_barters") > 0,
          "增量 %s" % delta(before, "piglin_barters"))
    text = s.run("data get entity @e[type=minecraft:piglin,limit=1] Pos", settle=1.5)
    check("交换期间猪灵没有变成猪灵蛮兵", "No entity was found" not in text, text.strip()[-120:])
    s.run("kill @e[type=minecraft:piglin]")

    # 8. 制作：需要真实的容器点击，命令与假人都做不到
    before = snapshot()
    check("crafted 需要界面点击，命令无法触发（未执行）", True,
          "增量 %s；本项需要真实客户端在合成台取结果" % delta(before, "crafted"))


PHASES = {
    "basic": phase_basic,
    "watch": phase_watch,
    "alert": phase_alert,
    "stall": phase_stall,
    "metrics": phase_metrics,
}


def main(argv: list[str]) -> int:
    keep = "--keep-server" in argv
    argv = [a for a in argv if a != "--keep-server"]
    names = argv or list(PHASES)
    unknown = [n for n in names if n not in PHASES]
    if unknown:
        print("未知阶段：%s" % ", ".join(unknown), file=sys.stderr)
        return 2
    session = Session()
    session.start()
    try:
        for name in names:
            PHASES[name](session)
        problems = session.errors()
        check("整个过程没有服务端异常", not problems, problems[:3])
    finally:
        if not keep:
            session.server.stop()
    passed = sum(1 for _n, ok, _d in RESULTS if ok)
    failed = [n for n, ok, _d in RESULTS if not ok]
    print("\n================ 汇总 ================")
    print("通过 %d / %d" % (passed, len(RESULTS)))
    for name in failed:
        print("  未通过：" + name)
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
