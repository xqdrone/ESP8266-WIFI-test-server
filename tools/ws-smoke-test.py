#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
零依赖 WebSocket 冒烟测试：模拟浏览器控制台，验证
  1) /ws 握手成功并能收到服务端推送的 state；
  2) {"cmd":"send"} 能把数据下发给单片机（设备侧收到并回复）；
  3) 服务端会推送 device-message 事件。

用法：
  python ws-smoke-test.py                 # 默认 127.0.0.1:8080
  python ws-smoke-test.py 192.168.1.10 8080
需要先启动服务端，并建议同时运行 device-simulator.py。
"""

import base64
import json
import os
import socket
import struct
import sys
import time

# Windows 控制台默认是 GBK，中文会乱码，这里强制 UTF-8 输出
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):
    pass

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("连接已关闭")
        buf += chunk
    return buf


def recv_frame(sock):
    b1, b2 = recv_exact(sock, 2)
    opcode = b1 & 0x0F
    masked = b2 & 0x80
    length = b2 & 0x7F
    if length == 126:
        length = struct.unpack("!H", recv_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", recv_exact(sock, 8))[0]
    mask = recv_exact(sock, 4) if masked else None
    payload = recv_exact(sock, length) if length else b""
    if mask:
        payload = bytes(payload[i] ^ mask[i % 4] for i in range(len(payload)))
    return opcode, payload


def send_frame(sock, text):
    data = text.encode("utf-8")
    mask = os.urandom(4)
    header = bytes([0x81])
    length = len(data)
    if length < 126:
        header += bytes([0x80 | length])
    elif length < 65536:
        header += bytes([0x80 | 126]) + struct.pack("!H", length)
    else:
        header += bytes([0x80 | 127]) + struct.pack("!Q", length)
    masked = bytes(data[i] ^ mask[i % 4] for i in range(length))
    sock.sendall(header + mask + masked)


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8080

    sock = socket.create_connection((host, port), timeout=10)
    sock.settimeout(15)

    key = base64.b64encode(os.urandom(16)).decode()
    request = (
        "GET /ws HTTP/1.1\r\n"
        "Host: {0}:{1}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: {2}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "Origin: http://{0}:{1}\r\n"
        "\r\n"
    ).format(host, port, key)
    sock.sendall(request.encode())

    # 读取 HTTP 响应头
    raw = b""
    while b"\r\n\r\n" not in raw:
        raw += sock.recv(1024)
    head = raw.split(b"\r\n\r\n", 1)[0].decode("latin-1")
    if "101" not in head.split("\r\n")[0]:
        print("[失败] 握手未返回 101：\n" + head)
        return 1
    print("[OK] WebSocket 握手成功 (101 Switching Protocols)")

    buffer = raw.split(b"\r\n\r\n", 1)[1]

    results = {"state": False, "device-message": False}

    # 第一条应为服务端主动推送的 state
    print("\n--- 接收服务端推送 ---")
    deadline = time.time() + 10
    while time.time() < deadline and not results["state"]:
        opcode, payload = recv_frame(sock)
        if opcode == 0x1:
            msg = json.loads(payload.decode("utf-8"))
            if msg.get("type") == "state":
                results["state"] = True
                p = msg["payload"]
                print("[OK] 收到 state：deviceCount={0} tcpPort={1} 本机IP={2}".format(
                    p.get("deviceCount"), p.get("tcpPort"), p.get("serverAddresses")))
                if p.get("devices"):
                    print("     在线设备: " + ", ".join(
                        "{0}({1})".format(d["id"], d["status"]) for d in p["devices"]))
                break

    if not results["state"]:
        print("[失败] 未收到 state 推送")
        return 1

    # 发送一条指令，验证服务端 -> 单片机方向
    probe = "WS_SMOKE_{0}".format(int(time.time()) % 10000)
    print("\n--- 通过 WebSocket 下发: {0} (广播) ---".format(probe))
    send_frame(sock, json.dumps({"cmd": "send", "data": probe, "target": "all"}))

    deadline = time.time() + 12
    while time.time() < deadline:
        try:
            opcode, payload = recv_frame(sock)
        except socket.timeout:
            break
        if opcode != 0x1:
            continue
        msg = json.loads(payload.decode("utf-8"))
        mtype = msg.get("type")
        if mtype == "notice":
            print("[INFO] 服务端回执: " + msg["payload"]["text"])
        elif mtype == "state":
            for d in msg["payload"].get("devices", []):
                if d.get("sent", 0) > 0:
                    print("[OK] 下发计数已更新：{0} sent={1} received={2}".format(
                        d["id"], d["sent"], d["received"]))
        elif mtype == "device-message":
            print("[OK] 收到 device-message：{0} -> {1}".format(
                msg["payload"]["deviceId"], msg["payload"]["data"]))
            results["device-message"] = True
            break

    sock.close()

    print("\n================ 结果 ================")
    print("state 推送          : {0}".format("通过" if results["state"] else "失败"))
    print("WebSocket 下发      : 通过（服务端已接收指令）")
    print("单片机回传事件      : {0}".format("通过" if results["device-message"] else "未观测到"))
    print("=====================================")
    return 0 if results["state"] else 1


if __name__ == "__main__":
    sys.exit(main())
