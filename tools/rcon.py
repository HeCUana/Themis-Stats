#!/usr/bin/env python3
"""Themis Stats 测试服的极简 RCON 客户端（纯标准库，无需 pip 安装）。

用法：
    python tools/rcon.py "themisstats top mining bot"
    python tools/rcon.py "carpet list themisStats" "themisstats help"

协议依据（原版 net.minecraft.server.rcon.thread.RconClient）：
  * 认证：请求 type=3，服务端回 type=2、body 为空表示成功；
  * 命令：请求 type=2，服务端用 type=0 回若干个最长 4096 字节的分片，
    并且**不会**补发空的结束包（只有输出本身为空时才会收到一个空包）。
    因此这里用「读到一个包之后再等一小段空闲时间」来判断响应结束。
"""

from __future__ import annotations

import socket
import struct
import sys
import time

# Windows 控制台默认是 GBK，服务端回的是 UTF-8，这里强制按 UTF-8 输出。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

HOST = "127.0.0.1"
PORT = 25575
PASSWORD = "themisstats"

TYPE_AUTH = 3
TYPE_COMMAND = 2
TYPE_RESPONSE = 0
TYPE_AUTH_RESPONSE = 2

# 收到第一个响应分片之后，再等这么久如果还没有新数据，就认为响应结束。
IDLE_TIMEOUT = 0.35
# 等待响应的第一个字节的上限。
FIRST_BYTE_TIMEOUT = 30.0


def _pack(request_id: int, packet_type: int, body: str) -> bytes:
    payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


class RconError(RuntimeError):
    pass


class Rcon:
    def __init__(self, host: str = HOST, port: int = PORT, password: str = PASSWORD,
                 timeout: float = FIRST_BYTE_TIMEOUT) -> None:
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.request_id = 0
        self._authenticate(password)

    # ---------------------------------------------------------------- 低层

    def _next_id(self) -> int:
        self.request_id += 1
        return self.request_id

    def _recv_exact(self, count: int) -> bytes:
        chunks = b""
        while len(chunks) < count:
            data = self.sock.recv(count - len(chunks))
            if not data:
                raise RconError("RCON 连接已被服务端关闭")
            chunks += data
        return chunks

    def _read_packet(self) -> tuple[int, int, str]:
        (length,) = struct.unpack("<i", self._recv_exact(4))
        payload = self._recv_exact(length)
        request_id, packet_type = struct.unpack("<ii", payload[:8])
        body = payload[8:-2]
        return request_id, packet_type, body.decode("utf-8", errors="replace")

    def _authenticate(self, password: str) -> None:
        self.sock.sendall(_pack(self._next_id(), TYPE_AUTH, password))
        _request_id, packet_type, body = self._read_packet()
        if packet_type != TYPE_AUTH_RESPONSE or body:
            raise RconError("RCON 认证失败：" + (body or "服务端未按协议回应"))

    # ---------------------------------------------------------------- 命令

    def command(self, command: str, wait: float = 0.0) -> str:
        self.sock.sendall(_pack(self._next_id(), TYPE_COMMAND, command))
        if wait > 0:
            time.sleep(wait)
        parts: list[str] = []
        self.sock.settimeout(FIRST_BYTE_TIMEOUT)
        try:
            while True:
                try:
                    _request_id, packet_type, body = self._read_packet()
                except socket.timeout:
                    break
                if packet_type != TYPE_RESPONSE:
                    break
                parts.append(body)
                # 已经有第一个分片了，后面用空闲超时判断响应是否结束。
                self.sock.settimeout(IDLE_TIMEOUT)
        finally:
            self.sock.settimeout(FIRST_BYTE_TIMEOUT)
        return "".join(parts)

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


def main(argv: list[str]) -> int:
    if not argv:
        print("用法：python tools/rcon.py \"<命令1>\" [\"<命令2>\" ...]", file=sys.stderr)
        return 2
    try:
        client = Rcon()
    except OSError as exc:
        print("无法连接 RCON（%s:%d）：%s" % (HOST, PORT, exc), file=sys.stderr)
        return 3
    except RconError as exc:
        print(str(exc), file=sys.stderr)
        return 3
    try:
        for index, command in enumerate(argv):
            if index:
                print("-" * 60)
            print("> " + command)
            try:
                output = client.command(command)
            except (OSError, RconError) as exc:
                print("命令执行失败：%s" % exc, file=sys.stderr)
                return 4
            if output.strip():
                print(output.rstrip("\n"))
            else:
                print("(无输出)")
    finally:
        client.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
