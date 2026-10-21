#!/usr/bin/env python3
"""用标准输入驱动 Minecraft 服务端控制台，让命令以「控制台」身份执行。

为什么需要它：RCON 的来源是一个特殊的 RconConsoleSource，它的输出被塞进一个只被读取一次的
缓冲区。Themis Stats 的查询命令把结果放在后台线程算完后再发回来，RCON 那时早就把缓冲区取走
了，所以 RCON 看不到查询结果；控制台来源则会把结果写进服务端日志。

用法（作为库）：
    from console_server import ConsoleServer
    with ConsoleServer() as server:
        server.send("themisstats fine mining minecraft:stone bot")
        print(server.wait_for("细分榜"))

用法（命令行）：
    python tools/console_server.py "themisstats bot bot_a" "themisstats explain mining"
"""

from __future__ import annotations

import os
import subprocess
import sys
import time

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(ROOT, "server")
LAUNCHER = os.path.join(SERVER, "fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar")
JAVA = r"C:\Program Files\Zulu\zulu-21\bin\java.exe"
LOG = os.path.join(SERVER, "logs", "latest.log")

# 26.3 需要 Java 25；仓库内自带的工具链优先，其次是 JAVA_HOME。
JAVA25 = os.path.join(ROOT, ".toolchains", "zulu25",
                      "zulu25.36.205-ca-jdk25.0.4.1-win_x64", "bin", "java.exe")


def java_for(server_dir: str) -> str:
    """选一个能跑该服务端目录的 Java。

    26.3 的服务端要求 Java 25；1.21.11 那个目录用 Java 21 即可。
    """
    name = os.path.basename(os.path.normpath(server_dir))
    if "263" in name.replace("-", "").replace("_", "") and os.path.isfile(JAVA25):
        return JAVA25
    if os.environ.get("JAVA_HOME"):
        candidate = os.path.join(os.environ["JAVA_HOME"], "bin", "java.exe")
        if os.path.isfile(candidate):
            return candidate
    return JAVA


def launcher_for(server_dir: str) -> str:
    """在服务端目录里找出 Fabric 启动器（不写死文件名，换 MC 版本也能用）。"""
    preferred = os.path.join(server_dir, "fabric-server-mc.1.21.11-loader.0.19.5-launcher.1.1.2.jar")
    if os.path.isfile(preferred):
        return preferred
    for name in sorted(os.listdir(server_dir)):
        if name.endswith(".jar") and "launcher" in name:
            return os.path.join(server_dir, name)
    raise RuntimeError("服务端目录里没有 Fabric 启动器：%s" % server_dir)


class ConsoleServer:
    """Starts the test server with a pipe on its standard input."""

    def __init__(self, root: str = ROOT, server_dir: str | None = None) -> None:
        self.root = root
        self.server = server_dir or os.environ.get("THEMIS_STATS_SERVER_DIR") or os.path.join(root, "server")
        self.server = os.path.abspath(self.server)
        os.makedirs(os.path.join(self.server, "logs"), exist_ok=True)
        self.launcher = launcher_for(self.server)
        self.log = os.path.join(self.server, "logs", "latest.log")
        self.proc: subprocess.Popen | None = None
        self._offset = 0

    # ---------------------------------------------------------------- 生命周期

    def start(self, wait: float = 180.0) -> "ConsoleServer":
        self.stop_server_only()
        out = open(os.path.join(self.server, "logs", "console-out.log"), "wb")
        err = open(os.path.join(self.server, "logs", "console-err.log"), "wb")
        # 启动器默认要去网上抓 loader 与游戏 jar；本机网络对大文件不稳，
        # 所以把本地已有的 loader jar 直接指给它。
        extra = []
        for name in sorted(os.listdir(self.server)):
            if name.startswith("fabric-loader-server-") and name.endswith(".jar"):
                extra.append("-Dfabric.customLoaderPath=" + os.path.join(self.server, name))
                break
        self.proc = subprocess.Popen(
            [java_for(self.server), "-Xms1G", "-Xmx2G", "-Djava.net.preferIPv4Stack=true"]
            + extra + ["-jar", self.launcher],
            cwd=self.server, stdin=subprocess.PIPE, stdout=out, stderr=err)
        # The server rotates latest.log when it starts, so the new file starts at 0 again.
        time.sleep(5)
        self._offset = 0
        deadline = time.time() + wait
        while time.time() < deadline:
            time.sleep(2)
            text = self._read_new()
            if "RCON running on" in text:
                return self
            if self.proc.poll() is not None:
                raise RuntimeError("服务端进程退出了，退出码 %s" % self.proc.returncode)
        raise RuntimeError("服务端没有在 %s 秒内就绪" % wait)

    def stop(self) -> None:
        if self.proc is not None and self.proc.poll() is None:
            try:
                self.send("stop")
            except OSError:
                pass
            for _ in range(30):
                if self.proc.poll() is not None:
                    break
                time.sleep(1)
            if self.proc.poll() is None:
                self.proc.kill()
        self.proc = None

    def stop_server_only(self) -> None:
        """Stops every running test server, also the ones started elsewhere."""
        script = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
                  "Where-Object { $_.CommandLine -like '*launcher*' } | "
                  "ForEach-Object { Stop-Process -Id $_.ProcessId -Force }")
        result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", script],
                                capture_output=True, text=True, errors="replace")
        if result.returncode == 0 and result.stdout.strip():
            time.sleep(8)

    def __enter__(self) -> "ConsoleServer":
        return self.start()

    def __exit__(self, *exc) -> None:
        self.stop()

    # ---------------------------------------------------------------- 命令

    def send(self, command: str) -> None:
        if self.proc is None or self.proc.stdin is None:
            raise RuntimeError("服务端没在运行")
        self.proc.stdin.write((command + "\n").encode("utf-8"))
        self.proc.stdin.flush()

    def _log_size(self) -> int:
        try:
            return os.path.getsize(self.log)
        except OSError:
            return 0

    def _read_new(self) -> str:
        try:
            with open(self.log, "r", encoding="utf-8", errors="replace") as handle:
                handle.seek(self._offset)
                text = handle.read()
                self._offset = handle.tell()
        except OSError:
            return ""
        return text

    def wait_for(self, needle: str, timeout: float = 30.0) -> str:
        """Waits until the needle shows up in the log and returns the new log text."""
        deadline = time.time() + timeout
        text = ""
        while time.time() < deadline:
            time.sleep(0.4)
            text += self._read_new()
            if needle in text:
                return text
        return text

    def drain(self) -> str:
        """Reads and returns everything that was logged since the last call."""
        time.sleep(0.6)
        return self._read_new()

    def read_all(self) -> str:
        """Reads the log from the very beginning and returns everything in it.

        The startup messages (the argument type self check for example) are read while the server
        starts, so a caller that wants them has to read the log again from the start.
        """
        self._offset = 0
        return self._read_new()


def main(argv: list[str]) -> int:
    if not argv:
        print("用法：python tools/console_server.py \"<命令1>\" [\"<命令2>\" ...]", file=sys.stderr)
        return 2
    server = ConsoleServer()
    server.start()
    try:
        for command in argv:
            server.send(command)
            print("> " + command)
            print(server.drain().rstrip("\n"))
            print("-" * 60)
    finally:
        server.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
