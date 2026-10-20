#!/usr/bin/env python3
"""一个最小的 Minecraft 1.21.11 协议客户端，用来验证「真实客户端能否进服」并读取聊天输出。

它不是游戏客户端：它只做握手、登录、配置阶段的应答，然后在游戏阶段回应 keep-alive、
发送命令、把系统聊天读出来。用来做两件事：
  1. 验证服务端对真实客户端的处理（命令树序列化、侧栏、聊天）；
  2. 给「只能由玩家执行」的命令（关注／取消关注）一个真正的玩家来源。

包 ID 来自原版 1.21.11 的协议注册顺序（_ref/mcsrc/net/minecraft/network/protocol）。

用法：
    python tools/mc_client.py "themisstats bot bot_a" "themisstats explain mining"
"""

from __future__ import annotations

import hashlib
import os
import socket
import struct
import sys
import threading
import time
import uuid as uuidlib

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

PROTOCOL = 774
HOST = "127.0.0.1"
PORT = 25565

# ---------------------------------------------------------------- 包 ID

HS_INTENTION = 0x00
LOGIN_HELLO = 0x00
LOGIN_ACK = 0x03
LOGIN_FINISHED = 0x02
LOGIN_DISCONNECT = 0x00
CONFIG_CLIENT_INFO = 0x00
CONFIG_SELECT_KNOWN_PACKS = 0x07
CONFIG_FINISH = 0x03
CONFIG_CB_SELECT_KNOWN_PACKS = 0x0E
CONFIG_CB_FINISH = 0x03
CONFIG_CB_DISCONNECT = 0x02
CONFIG_CB_KEEP_ALIVE = 0x04
CONFIG_SB_KEEP_ALIVE = 0x04
PLAY_SB_CHAT_COMMAND = 0x06
PLAY_SB_KEEP_ALIVE = 0x1B
PLAY_CB_KEEP_ALIVE = 0x2A
PLAY_CB_SYSTEM_CHAT = 0x76
PLAY_CB_DISCONNECT = 0x1F


def offline_uuid(name: str) -> uuidlib.UUID:
    """The UUID the server gives to an offline mode player (Java's nameUUIDFromBytes)."""
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuidlib.UUID(bytes=bytes(digest))


class Buffer:
    """A reader for the Minecraft wire format."""

    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0

    def read(self, count: int) -> bytes:
        if self.pos + count > len(self.data):
            raise EOFError("数据不够：还剩 %d 字节，要 %d 字节" % (len(self.data) - self.pos, count))
        chunk = self.data[self.pos:self.pos + count]
        self.pos += count
        return chunk

    def read_varint(self) -> int:
        value = 0
        for shift in range(0, 35, 7):
            byte = self.read(1)[0]
            value |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return value
        raise ValueError("varint 太长")

    def read_utf(self) -> str:
        length = self.read_varint()
        return self.read(length * 2).decode("utf-16-be")

    def read_string(self) -> str:
        length = self.read_varint()
        return self.read(length).decode("utf-8")

    def read_long(self) -> int:
        return struct.unpack(">q", self.read(8))[0]

    def read_int(self) -> int:
        return struct.unpack(">i", self.read(4))[0]

    def read_byte(self) -> int:
        return self.read(1)[0]

    def read_bool(self) -> bool:
        return self.read(1)[0] != 0

    def read_uuid(self) -> str:
        return str(uuidlib.UUID(bytes=self.read(16)))

    def remaining(self) -> int:
        return len(self.data) - self.pos

    # ---------------------------------------------------------------- NBT

    def read_nbt(self, tag_type: int = -1) -> object:
        if tag_type < 0:
            tag_type = self.read_byte()
        if tag_type == 0:
            return None
        if tag_type == 1:
            return struct.unpack(">b", self.read(1))[0]
        if tag_type == 2:
            return struct.unpack(">h", self.read(2))[0]
        if tag_type == 3:
            return self.read_int()
        if tag_type == 4:
            return self.read_long()
        if tag_type == 5:
            return struct.unpack(">f", self.read(4))[0]
        if tag_type == 6:
            return struct.unpack(">d", self.read(8))[0]
        if tag_type == 7:
            return list(self.read(self.read_int()))
        if tag_type == 8:
            return self.read_string()
        if tag_type == 9:
            item_type = self.read_byte()
            count = self.read_int()
            return [self.read_nbt(item_type) for _ in range(count)]
        if tag_type == 10:
            result: dict[str, object] = {}
            while True:
                entry_type = self.read_byte()
                if entry_type == 0:
                    return result
                name = self.read_string()
                result[name] = self.read_nbt(entry_type)
        if tag_type == 11:
            return [self.read_int() for _ in range(self.read_int())]
        if tag_type == 12:
            return [self.read_long() for _ in range(self.read_int())]
        raise ValueError("未知的 NBT 标签类型 %d" % tag_type)


def component_text(value: object) -> str:
    """Turns a decoded NBT component into plain text."""
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "".join(component_text(item) for item in value)
    if isinstance(value, dict):
        if "text" in value:
            text = str(value["text"])
        elif "translate" in value:
            text = str(value["translate"])
        else:
            text = ""
        extra = value.get("extra")
        if isinstance(extra, list):
            text += "".join(component_text(item) for item in extra)
        return text
    return str(value)


class MinecraftClient:
    """The smallest client that can join a server and run commands."""

    def __init__(self, name: str, host: str = HOST, port: int = PORT) -> None:
        self.name = name
        self.host = host
        self.port = port
        self.sock: socket.socket | None = None
        self.chat: list[str] = []
        self.errors: list[str] = []
        self.joined = False
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._reader: threading.Thread | None = None

    # ---------------------------------------------------------------- 连接

    def connect(self, timeout: float = 60.0) -> "MinecraftClient":
        self.sock = socket.create_connection((self.host, self.port), timeout=timeout)
        self.sock.settimeout(timeout)
        self._send_handshake()
        self._send_login()
        self._read_login()
        self._configuration()
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()
        return self

    def close(self) -> None:
        self._stop.set()
        if self.sock is not None:
            try:
                self.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    # ---------------------------------------------------------------- 发送

    def _write(self, packet_id: int, payload: bytes = b"") -> None:
        body = _varint(packet_id) + payload
        assert self.sock is not None
        self.sock.sendall(_varint(len(body)) + body)

    def _send_handshake(self) -> None:
        payload = _varint(PROTOCOL) + _utf(self.host) + struct.pack(">H", self.port) + _varint(2)
        self._write(HS_INTENTION, payload)

    def _send_login(self) -> None:
        payload = _utf(self.name) + offline_uuid(self.name).bytes
        self._write(LOGIN_HELLO, payload)

    def _send_client_information(self) -> None:
        payload = (_utf("zh_cn") + bytes([8]) + _varint(0) + bytes([1]) + bytes([0])
                   + _varint(1) + bytes([0]) + bytes([0]) + _varint(0))
        self._write(CONFIG_CLIENT_INFO, payload)

    def _send_known_packs(self, packs: list[tuple[str, str, str]]) -> None:
        payload = _varint(len(packs))
        for namespace, identifier, version in packs:
            payload += _string(namespace) + _string(identifier) + _string(version)
        self._write(CONFIG_SELECT_KNOWN_PACKS, payload)

    def send_command(self, command: str) -> None:
        """Sends a chat command. The command must not have a leading slash."""
        self._write(PLAY_SB_CHAT_COMMAND, _utf(command.lstrip("/")))

    # ---------------------------------------------------------------- 读取

    def _read_packet(self) -> tuple[int, bytes]:
        assert self.sock is not None
        header = b""
        while len(header) < 1:
            chunk = self.sock.recv(1)
            if not chunk:
                raise ConnectionError("服务端关闭了连接")
            header += chunk
        length = 0
        shift = 0
        value = header[0]
        while True:
            length |= (value & 0x7F) << shift
            if not value & 0x80:
                break
            shift += 7
            more = self.sock.recv(1)
            if not more:
                raise ConnectionError("服务端关闭了连接")
            value = more[0]
        data = b""
        while len(data) < length:
            chunk = self.sock.recv(length - len(data))
            if not chunk:
                raise ConnectionError("服务端关闭了连接")
            data += chunk
        buf = Buffer(data)
        packet_id = buf.read_varint()
        return packet_id, data[buf.pos:]

    def _read_login(self) -> None:
        while True:
            packet_id, payload = self._read_packet()
            if packet_id == LOGIN_FINISHED:
                self._write(LOGIN_ACK)
                return
            if packet_id == LOGIN_DISCONNECT:
                buf = Buffer(payload)
                raise RuntimeError("登录被拒绝：" + component_text(buf.read_nbt()))

    def _configuration(self) -> None:
        self._send_client_information()
        while True:
            packet_id, payload = self._read_packet()
            if packet_id == CONFIG_CB_SELECT_KNOWN_PACKS:
                buf = Buffer(payload)
                count = buf.read_varint()
                packs = []
                for _ in range(count):
                    namespace = buf.read_string()
                    identifier = buf.read_string()
                    version = buf.read_string()
                    packs.append((namespace, identifier, version))
                self._send_known_packs(packs)
            elif packet_id == CONFIG_CB_FINISH:
                self._write(CONFIG_FINISH)
                return
            elif packet_id == CONFIG_CB_KEEP_ALIVE:
                buf = Buffer(payload)
                self._write(CONFIG_SB_KEEP_ALIVE, struct.pack(">q", buf.read_long()))
            elif packet_id == CONFIG_CB_DISCONNECT:
                buf = Buffer(payload)
                raise RuntimeError("配置阶段被断开：" + component_text(buf.read_nbt()))
            elif packet_id == 0x01:  # custom payload
                pass

    def _read_loop(self) -> None:
        try:
            while not self._stop.is_set():
                packet_id, payload = self._read_packet()
                if packet_id == PLAY_CB_KEEP_ALIVE:
                    buf = Buffer(payload)
                    self._write(PLAY_SB_KEEP_ALIVE, struct.pack(">q", buf.read_long()))
                elif packet_id == PLAY_CB_SYSTEM_CHAT:
                    buf = Buffer(payload)
                    text = component_text(buf.read_nbt())
                    buf.read_bool()
                    with self._lock:
                        self.chat.append(text)
                elif packet_id == PLAY_CB_DISCONNECT:
                    buf = Buffer(payload)
                    with self._lock:
                        self.errors.append("被断开：" + component_text(buf.read_nbt()))
                    return
                elif packet_id == 0x2F:  # login (play)
                    self.joined = True
        except (OSError, EOFError, ConnectionError) as exc:
            with self._lock:
                self.errors.append("连接结束：%s" % exc)

    # ---------------------------------------------------------------- 结果

    def chat_text(self) -> str:
        with self._lock:
            return "\n".join(self.chat)

    def wait_for_chat(self, needle: str, timeout: float = 15.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if needle in self.chat_text():
                return True
            time.sleep(0.3)
        return False


def _varint(value: int) -> bytes:
    out = b""
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out += bytes([byte | 0x80])
        else:
            out += bytes([byte])
            return out


def _utf(text: str) -> bytes:
    data = text.encode("utf-16-be")
    return _varint(len(text)) + data


def _string(text: str) -> bytes:
    data = text.encode("utf-8")
    return _varint(len(data)) + data


def main(argv: list[str]) -> int:
    if not argv:
        print("用法：python tools/mc_client.py \"<命令1>\" [\"<命令2>\" ...]", file=sys.stderr)
        return 2
    name = os.environ.get("MC_CLIENT_NAME", "Tester_%d" % (int(time.time()) % 10000))
    client = MinecraftClient(name)
    client.connect()
    print("已连接：%s（uuid %s）" % (name, offline_uuid(name)), flush=True)
    try:
        for command in argv:
            client.send_command(command)
            time.sleep(1.5)
            print("> " + command)
            print(client.chat_text())
            print("-" * 60)
    finally:
        client.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
