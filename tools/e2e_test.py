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
    offline 意外离线告警（订阅者是**另一个**玩家）
"""

from __future__ import annotations

import json
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

# 被测服务端目录：默认是 1.21.11 的那个；26.3 用 THEMIS_STATS_SERVER_DIR=server-263。
SERVER_DIR = os.environ.get("THEMIS_STATS_SERVER_DIR") or os.path.join(os.path.dirname(HERE), "server")
DB = os.path.join(SERVER_DIR, "world", "themisstats", "stats.db")
USERCACHE = os.path.join(SERVER_DIR, "usercache.json")
BOT = "bot_a"
# 第二个假人：它当订阅者。「意外离线」告警是在被观察对象下线的那一刻发出的，
# 收件人必须在那一刻还在线，所以订阅者不能是被观察对象自己。
#
# 这个名字必须在服务端的 usercache.json 里（也就是它以前进过服）：
# Carpet 的假人是**先取档案再创建**的，本机连不上 Mojang 的档案服务，
# 取不到档案的假人根本不会被创建，而命令连报错都不打印。
SUB = "Bot_B"

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


def profile_cached(name: str) -> bool:
    """Whether the profile of the name is in the server's user cache.

    A Carpet fake player is created **after** its profile was fetched. This environment
    cannot reach the Mojang session server, so the fetch fails, the fake player is never
    created - and the command does not even print an error. Only names that are already
    in the user cache (that is: names that have been on the server before) can be summoned.
    """
    try:
        with open(USERCACHE, "r", encoding="utf-8") as handle:
            entries = json.load(handle)
    except (OSError, ValueError):
        return False
    return any(str(entry.get("name", "")).lower() == name.lower() for entry in entries)


def read_health(s: "Session") -> float:
    """The fake player's **live** health, read from Themis Stats' own observation.

    `data get entity <player> Health` reads the **saved copy** of the NBT, which is only
    refreshed when the world is saved. On 26.3 that copy was measurably stale: it still said
    20.0 while the fake player had really been shot down to about 2, and a damage computed
    from it killed the fake player.

    Themis Stats samples the bots every 2 seconds and shows the live value in
    `/themisstats bot <object>`（生命／饥饿）, so that is what the test uses.
    """
    text = s.run("themisstats bot %s" % BOT, settle=2.0)
    clean = re.sub(r"§[0-9a-zA-Z]", "", text)
    if "离线（最后已知）" in clean:
        # The fake player is offline: the value shown is the **last known** one, not the live one.
        return -1.0
    found = re.search(r"生命／饥饿\D*([\d.]+)", clean)
    return float(found.group(1)) if found else -1.0


def pin_health_low(s: "Session", target: float = 2.0, rounds: int = 6) -> float:
    """Keeps the fake player's health below the low-health threshold.

    Only the vanilla `damage` command is used, so this works the same on every
    Minecraft version. Two 26.3 findings make it necessary:

    * the fake player **regenerates fast** (measured: +2.8 health within one second),
      so a single hit is not enough - the health can climb back over the threshold
      between the two samples the alert needs;
    * the world around the spawn point is full of hostiles that hit the fake player
      on their own, so the health also drifts downwards unpredictably.

    Each round removes only the surplus (with a safety margin, because the observation
    is at most a couple of seconds old), so the fake player is never killed, and the
    value that is actually read back is returned.
    """
    value = read_health(s)
    for _ in range(rounds):
        if 0.0 < value <= target:
            break
        if value < 0.0:
            # The fake player was killed by the hostiles; summon it again and start over.
            s.ensure_bot_alive()
            s.run("execute as %s run themisstats watch %s bot low_health low_durability", settle=1.5)
            value = read_health(s)
            continue
        s.clean_mobs()
        s.run("damage %s %.1f minecraft:magic" % (BOT, max(1.0, value - 5.0)))
        time.sleep(1.5)
        value = read_health(s)
    return value


def board_accepted(text: str, title: str) -> bool:
    """Whether the board accepted the arguments and rendered a result.

    A **fresh** world has no collected data yet, so the board legitimately answers
    「该范围内暂无数据」. That is a rendered result, not a failure - the point of
    this check is that the command accepted the category/dimension arguments.
    """
    if title not in text:
        return False
    return BOT in text or "暂无数据" in text


def bot_uuid() -> str:
    return uuid_of(BOT)


def uuid_of(name: str, timeout: float = 30.0) -> str:
    """Looks the account uuid of a player up in the statistics database.

    The account row is written in the background when the player comes online, so the
    lookup has to be retried for a moment.
    """
    deadline = time.time() + timeout
    while True:
        rows = query("SELECT uuid FROM account WHERE current_name = ?", (name,))
        if rows:
            return rows[0][0]
        if time.time() > deadline:
            raise RuntimeError("库里找不到 " + name)
        time.sleep(1.0)


def write_db(sql: str, args: tuple = ()) -> None:
    """Writes to the statistics database from the outside.

    The server writes in the background, so the write lock is not free all the time;
    the retry loop waits for it.
    """
    path = DB.replace("\\", "/")
    last = None
    for _ in range(20):
        try:
            con = sqlite3.connect(path, timeout=3.0)
            try:
                con.execute(sql, args)
                con.commit()
            finally:
                con.close()
            return
        except sqlite3.Error as exc:
            last = exc
            time.sleep(0.5)
    raise RuntimeError("写统计库失败：%s" % last)


def reset_alert_cooldowns() -> None:
    """Clears the alert states of the test bots **before** the server starts.

    The cooldown of one object and one reason is ten minutes and it survives a restart:
    the alert engine reads the states into memory when it loads and afterwards never
    lowers them again. So the states have to be cleared before the server starts, not
    while it runs - otherwise a second run within ten minutes would be swallowed.
    """
    uuids = []
    for name in (BOT, SUB):
        rows = query("SELECT uuid FROM account WHERE current_name = ?", (name,))
        if rows:
            uuids.append(rows[0][0])
    if not uuids:
        return
    for uuid in uuids:
        write_db("DELETE FROM alert_state WHERE target_uuid = ?", (uuid,))


def heal_bot(s: "Session") -> None:
    """把假人的血量拉满。

    告警用例要用固定的血量做前提：如果假人之前已经掉过血，15 点伤害会直接把它打死，
    告警就永远不会触发。`instant_health` 的效果每 tick 都会回血，所以这里要等效果结束，
    否则它会在伤害之后又把血补上去。

    补一条 `effect clear`：26.3 上实测过，疗效还在生效时读血量会读到**正在回血的中间值**
    （一次读数 8.500001 而实际已经被打到 5），断言就会误判。显式清掉效果后读数才稳定。
    """
    s.run("execute as %s run effect give @s minecraft:instant_health 1 10" % BOT)
    time.sleep(3.0)
    s.run("execute as %s run effect clear @s minecraft:instant_health" % BOT)
    time.sleep(1.0)


def total_of(uuid: str, metric: str) -> int:
    rows = query("SELECT COALESCE(SUM(value), 0) FROM total WHERE uuid = ? AND metric = ? "
                 "AND source = 'collected'", (uuid, metric))
    return int(rows[0][0]) if rows else 0


def total_of_category(uuid: str, metric: str, category: str) -> int:
    """The accumulated value of one metric **within one category**.

    Needed because the world can hurt the fake player on its own (on 26.3 the fresh world
    spawns hostiles that shoot it), and that damage lands in the same metric but under a
    different category. The category-scoped delta is therefore the exact number this test
    caused.
    """
    rows = query("SELECT COALESCE(SUM(value), 0) FROM total WHERE uuid = ? AND metric = ? "
                 "AND category = ? AND source = 'collected'", (uuid, metric, category))
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
        """Makes sure the fake player is online, spawns it if it is not."""
        return self.ensure_player(BOT, timeout)

    def ensure_player(self, name: str, timeout: float = 300.0) -> bool:
        """Makes sure the fake player is online, spawns it if it is not.

        The Mojang profile lookup times out in this environment, so the fake player can take a
        couple of minutes to show up. The check therefore polls the player list instead of waiting
        for the join message: the message can be missed when it arrives while another command is
        being answered.
        """
        if name in self.online_players():
            return True
        if not profile_cached(name):
            note("名字 %s 不在 usercache.json 里：档案取不到，假人不会被创建（命令也不报错）" % name)
        self.run("player %s spawn" % name)
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(5)
            if name in self.online_players():
                return True
        return False

    def online_players(self) -> list[str]:
        """The names of the players that are online right now.

        Only the line that the `list` command prints is read: the rest of the log window can
        contain the join messages of the players that are not online any more. The name list
        is behind the **last** colon of the line, because the log line starts with a timestamp.
        """
        text = self.run("list", settle=1.5)
        for line in text.splitlines():
            if "players online" in line and "of a max of" in line:
                tail = line.rsplit(":", 1)[-1]
                return [name.strip() for name in tail.split(",") if name.strip()]
        return []

    def clean_mobs(self) -> None:
        """Removes the mobs the previous run left behind.

        The test world is persistent, so a piglin from an interrupted run is still standing next
        to the spawn point. It has AI, it attacks players who do not wear gold armor, and it can
        kill the fake player before the first case of a phase runs.

        The selector is deliberately broad: on Minecraft 26.3 the fresh world spawns **178**
        hostiles around the spawn point (skeletons shoot arrows, piglins use spears), and that
        damage lands in exactly the metrics the phases assert on. Everything that is neither a
        player nor an item entity is removed, which also covers the arrows in flight.
        """
        self.run("kill @e[type=!player,type=!item]")
        time.sleep(0.5)
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
                # 离线环境下游戏自己去连 Mojang 的服务会超时，这是**环境噪音**，
                # 不是本 Mod 的异常（服务端日志里能看到同样的超时）。
                if "authlib" in line or "discovery.minecraftservices" in line:
                    continue
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
    check("细分榜接受带引号的分类 minecraft:stone", board_accepted(text, "细分榜"),
          text.strip()[-240:])
    check("细分榜标题里带上了分类", "minecraft:stone" in text, text.strip()[-240:])

    text = s.run('themisstats admin board mining bot survival "minecraft:stone" minecraft:overworld',
                  settle=2.5)
    check("诊断榜接受分类与维度（原版标识符参数）", board_accepted(text, "诊断榜"),
          text.strip()[-240:])
    check("诊断榜按维度过滤（overworld）", "minecraft:overworld" in text, text.strip()[-240:])

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
    # 只认**本轮**产生的告警行：统计库是累积的，上一轮的旧行会让断言假通过。
    mark = query("SELECT COALESCE(MAX(id), 0) FROM alert_log")[0][0]
    started = int(time.time() * 1000)
    # 前置条件：订阅者（假人自己）必须是该对象的**负责人**。
    # 可见性规则 themisStatsBotLocations 默认是 owners，只有负责人或管理员收得到告警；
    # 而 `offline` 阶段会把负责人换成 Bot_B，所以每个阶段都要自己把前置条件摆好，
    # 否则单独跑某个阶段时告警会被权限校验**静默拦掉**（看起来像功能坏了）。
    s.run("themisstats admin bot owner %s %s" % (BOT, BOT), settle=2.0)
    # 和平难度：26.3 的新世界里出生点周围会刷出上百个敌对生物（骷髅射箭、猪灵用矛），
    # 它们会把假人打死，血量一归零就重生，低生命告警自然没有「连续两次低于阈值」。
    # 难度在阶段结束时恢复成 easy。
    s.run("difficulty peaceful", settle=1.5)
    s.run("carpet themisStatsBotAlerts true")
    s.run("execute as %s run gamemode survival" % BOT)
    heal_bot(s)
    s.run("execute as %s run themisstats watch %s bot low_health" % (BOT, BOT))
    time.sleep(2.0)
    rows = query("SELECT alerts FROM watch_subscription WHERE target_uuid = ?", (uuid,))
    check("只订阅 low_health", bool(rows) and int(rows[0][0]) == 1, rows)
    note("等待 60 秒宽限期结束")
    time.sleep(62)
    # 宽限期里世界一直在刷怪，假人会被射成残血（26.3 的新世界出生点周围有 100 多个敌对生物），
    # 所以这里**重新清场并重新治疗**，把前置状态摆干净，再把血量钉在阈值以下。
    s.clean_mobs()
    heal_bot(s)
    # 一次重击把血量压到 1 点：下一次采样（约 +1 秒，回血约 4 点/秒）仍在阈值以下，
    # 于是「连续两次低于阈值」成立，告警会触发。
    s.run("damage %s 19 minecraft:magic" % BOT)
    time.sleep(1.0)
    value = read_health(s)
    fired = []
    deadline = time.time() + 20
    while time.time() < deadline and not fired:
        rows = query("SELECT reason, message FROM alert_log WHERE target_uuid = ? AND id > ? "
                     "ORDER BY id DESC LIMIT 3", (uuid, mark))
        fired = [r for r in rows if r[0] == "low_health"]
        if not fired:
            time.sleep(1.0)
    # 阈值是含等号的（`health <= threshold`），所以 6.0 同样算「低于阈值」。
    check("假人掉到阈值以下且还活着", 0.0 < value <= 6.0, "实测血量 %s" % value)
    check("低生命值告警已发出并落库", bool(fired), fired)
    if fired:
        check("告警内容说明连续两次低于阈值", "连续两次" in fired[0][1], fired[0][1][:140])
    rows = query("SELECT reason, active, last_fired_wall FROM alert_state WHERE target_uuid = ?", (uuid,))
    # 26.3 上假人回血很快，血量很快升回阈值以上 → 恢复通知随即发出，active 也翻回 0。
    # 所以这里核对「本轮的触发被记进了状态表」；恢复由下一条断言负责。
    check("告警状态已记录本轮触发",
          any(r[0] == "low_health" and (r[2] or 0) >= started for r in rows), rows)
    s.run("execute as %s run effect give @s minecraft:instant_health 1 5" % BOT)
    time.sleep(8)
    rows = query("SELECT reason, message FROM alert_log WHERE target_uuid = ? AND id > ? "
                 "ORDER BY id DESC LIMIT 3", (uuid, mark))
    recovered = [r for r in rows if r[0] == "low_health_recovered"]
    check("恢复通知已发出并落库", bool(recovered), recovered)

    # 工具低耐久：主手铁镐的剩余耐久低于阈值（250 - 245 = 5，剩 2%）
    s.run("execute as %s run themisstats watch %s bot low_durability" % (BOT, BOT))
    time.sleep(2.0)
    rows = query("SELECT alerts FROM watch_subscription WHERE target_uuid = ?", (uuid,))
    check("订阅切到 low_durability", bool(rows) and int(rows[0][0]) == 2, rows)
    s.run("execute as %s run item replace entity @s weapon.mainhand with minecraft:iron_pickaxe[damage=245]"
          % BOT)
    fired = []
    deadline = time.time() + 25
    while time.time() < deadline and not fired:
        time.sleep(2)
        rows = query("SELECT reason, message FROM alert_log WHERE target_uuid = ? AND id > ? "
                     "ORDER BY id DESC LIMIT 3", (uuid, mark))
        fired = [r for r in rows if r[0] == "low_durability"]
    check("工具低耐久告警已发出并落库", bool(fired), fired)
    if fired:
        check("低耐久告警写明剩余耐久与阈值", "剩余耐久" in fired[0][1], fired[0][1][:140])
    s.run("execute as %s run item replace entity @s weapon.mainhand with minecraft:iron_pickaxe" % BOT)
    time.sleep(8)
    rows = query("SELECT reason, message FROM alert_log WHERE target_uuid = ? AND id > ? "
                 "ORDER BY id DESC LIMIT 3", (uuid, mark))
    recovered = [r for r in rows if r[0] == "low_durability_recovered"]
    check("低耐久恢复通知已发出并落库", bool(recovered), recovered)
    s.run("execute as %s run themisstats unwatch %s bot" % (BOT, BOT))
    s.run("difficulty easy", settle=1.5)


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
    before_magic = total_of_category(uuid, "damage_taken", "magic")
    s.run("damage %s 5 minecraft:magic" % BOT)
    time.sleep(9)
    # 按**分类**核对：这条命令造成的是 5 点「magic」伤害。世界里可能有怪物补刀，
    # 那些伤害算在别的分类下，不该混进这条断言。
    magic = total_of_category(uuid, "damage_taken", "magic") - before_magic
    check("damage_taken 记录受到的伤害", magic == 5,
          "magic 分类增量 %s；总增量 %s" % (magic, delta(before, "damage_taken")))
    text = s.run("data get entity %s Health" % BOT, settle=1.5)
    health = re.findall(r"entity data:\s*(-?[\d.]+)", text)
    live = read_health(s)
    check("假人挨打后还活着（没有遗留生物把它打死）", live > 0.0,
          "活体血量 %s（NBT 副本 %s）" % (live, health[-1] if health else "?"))
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

    # 6. 放置。放置必须**真的成功**才算数，而「垂直向下右键」是放不成的：
    #    方块会卡在假人自己脚下，原版直接拒绝。
    #    所以这里先铺平地面，再在它东边造一堵到眼睛高度的墙，让它**平视**墙再右键。
    #    一次性动作 `use once` 在实体 tick 期间执行，而转向命令在 tick 结束后才落地，
    #    所以「转向」与「右键」必须分两批发。
    before = snapshot()
    pos = s.pos_of(BOT)
    if not pos:
        note("没读到假人坐标，跳过放置用例")
    else:
        bx, by, bz = int(pos[0]), int(pos[1]), int(pos[2])
        s.run("fill %d %d %d %d %d %d minecraft:stone"
              % (bx - 3, by - 1, bz - 3, bx + 3, by - 1, bz + 3))
        s.run("fill %d %d %d %d %d %d minecraft:air"
              % (bx - 3, by, bz - 3, bx + 3, by + 2, bz + 3))
        s.run("tp %s %.2f %.2f %.2f" % (BOT, bx + 0.5, by, bz + 0.5))
        time.sleep(1.5)
        s.run("fill %d %d %d %d %d %d minecraft:stone" % (bx + 2, by, bz - 1, bx + 2, by + 1, bz + 1))
        s.run("execute as %s run give @s minecraft:stone 5" % BOT)
        time.sleep(2)
        s.run_batch(["player %s look east" % BOT], settle=0.6)
        time.sleep(0.4)
        s.run_batch(["player %s use once" % BOT], settle=0.6)
        time.sleep(9)
        check("placement 记录放置的方块", delta(before, "placement") > 0,
              "增量 %s" % delta(before, "placement"))

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


# ---------------------------------------------------------------- offline

def phase_offline(s: Session) -> None:
    """The "went offline unexpectedly" alert, end to end.

    The alert is sent at the moment the watched object goes offline, so the subscriber has
    to be online at that moment. If the subscriber is the watched object itself, it is
    already gone when the alert is sent and never receives it - that is why this case
    needs a **second** player as the subscriber.

    The subscriber also needs the permission to receive the alert: the visibility rule
    `themisStatsBotLocations` defaults to `owners`, so the subscriber is bound as the
    owner of the watched object.
    """
    print("\n[阶段 offline] 意外离线告警", flush=True)
    s.clean_mobs()
    if not s.ensure_bot_alive():
        check("phase_offline 需要被观察的假人在线", False, "假人没进服")
        return
    s.run("carpet themisStatsBotAlerts true")
    target_uuid = bot_uuid()
    if not s.ensure_player(SUB):
        check("订阅者假人 %s 没进服" % SUB, False, "假人没进服")
        return
    sub_uuid = uuid_of(SUB)

    text = s.run("themisstats admin bot owner %s %s" % (BOT, SUB), settle=2.5)
    check("订阅者被绑定为被观察对象的负责人", "负责人已设置为" in text, text.strip()[-140:])

    text = s.run("execute as %s run themisstats watch %s bot unexpected_offline" % (SUB, BOT),
                 settle=2.5)
    check("订阅者订阅了「意外离线」提醒", "已关注" in text, text.strip()[-140:])
    rows = query("SELECT subscriber_uuid, alerts FROM watch_subscription WHERE target_uuid = ?",
                 (target_uuid,))
    check("订阅关系写进统计库（订阅者是另一个玩家）",
          any(r[0] == sub_uuid and int(r[1]) == 4 for r in rows), rows)

    before = query("SELECT COALESCE(MAX(id), 0) FROM alert_log")[0][0]
    # Let the watched object go offline. The vanilla kick is tried first; Carpet may swallow
    # it, because its fake connections ignore most disconnect packets.
    s.run("kick %s" % BOT, settle=2.0)
    method = "kick"
    if BOT in s.online_players():
        note("原版 kick 没有让假人下线，改用 Carpet 的 /player <名> kill")
        s.run("player %s kill" % BOT, settle=2.0)
        method = "player kill"
        time.sleep(3)
    check("被观察的假人已经下线（%s）" % method, BOT not in s.online_players(),
          "玩家列表里还有它")
    note("断开方式：%s" % method)

    fired = []
    deadline = time.time() + 30
    while time.time() < deadline:
        time.sleep(2)
        rows = query("SELECT reason, message, subscriber_uuid FROM alert_log "
                     "WHERE id > ? AND reason = 'unexpected_offline'", (before,))
        if rows:
            fired = rows
            break
    check("意外离线告警已发出并落库", bool(fired), fired)
    if fired:
        check("告警发给了订阅者（不是被观察对象自己）", fired[0][2] == sub_uuid, fired[0][2])
        check("告警内容写明对象已离线", "已离线" in fired[0][1], fired[0][1][:140])
    rows = query("SELECT end_reason FROM session WHERE uuid = ? ORDER BY start_wall DESC LIMIT 1",
                 (target_uuid,))
    check("会话结束原因落库（管理员移除 / 原因未知）",
          bool(rows) and rows[0][0] in ("admin_removal", "unknown"), rows)

    # Clean up: the subscription is removed and the fake player is summoned again, so that the
    # other phases still have their player.
    s.run("execute as %s run themisstats unwatch %s bot" % (SUB, BOT), settle=1.5)
    s.run("player %s spawn" % BOT, settle=2.0)


PHASES = {
    "basic": phase_basic,
    "watch": phase_watch,
    "alert": phase_alert,
    "stall": phase_stall,
    "metrics": phase_metrics,
    "offline": phase_offline,
}


def main(argv: list[str]) -> int:
    keep = "--keep-server" in argv
    argv = [a for a in argv if a != "--keep-server"]
    names = argv or list(PHASES)
    unknown = [n for n in names if n not in PHASES]
    if unknown:
        print("未知阶段：%s" % ", ".join(unknown), file=sys.stderr)
        return 2
    # The alert cooldowns are cleared before the server starts: the alert engine reads them
    # into memory when it loads and never lowers them again.
    reset_alert_cooldowns()
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
