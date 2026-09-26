#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ESP8266 / 单片机 模拟器
========================

没有硬件时，用它在 PC 上模拟一台已经连上 WiFi 的单片机，用来验证整套链路：
浏览器按钮 -> SpringBoot 服务端 -> TCP -> “单片机”。

行为：
  1. 连接服务端 TCP 端口（默认 127.0.0.1:9000）；
  2. 发送一行上线握手数据；
  3. 每 5 秒发送一次心跳（服务端靠它判定在线/离线）；
  4. 收到服务端下发的每一行数据就打印出来，并按指令回一条 ACK。

用法：
  python device-simulator.py                          # 默认 127.0.0.1:9000
  python device-simulator.py 192.168.1.10 9000        # 指定服务端
  python device-simulator.py --name sensor-01         # 指定设备名
  python device-simulator.py --no-heartbeat           # 不发心跳，用来演示“心跳超时”判离线
  python device-simulator.py --drop-after 20          # 20 秒后主动断开，用来演示掉线

按 Ctrl+C 退出。
"""

import argparse
import json
import socket
import sys
import threading
import time

HEARTBEAT_SECONDS = 5

# Windows 控制台默认是 GBK，中文/UTF-8 会变成乱码，这里强制用 UTF-8 输出。
# 另外：输出被重定向到管道/文件时 Python 会改成块缓冲，日志会延迟很久才出现，
# 所以顺便关掉缓冲，方便边跑边看。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    sys.stderr.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
except (AttributeError, ValueError):
    pass


def build_parser():
    parser = argparse.ArgumentParser(description="ESP8266 / 单片机 TCP 模拟器")
    parser.add_argument("host", nargs="?", default="127.0.0.1", help="服务端 IP（默认 127.0.0.1）")
    parser.add_argument("port", nargs="?", type=int, default=9000, help="服务端 TCP 端口（默认 9000）")
    parser.add_argument("--name", default="esp8266-sim", help="设备名，出现在上线报文里")
    parser.add_argument("--no-heartbeat", action="store_true", help="不发送心跳")
    parser.add_argument("--drop-after", type=float, default=0.0, help="N 秒后主动断开连接")
    parser.add_argument("--charset", default="utf-8",
                        help="收发的字符集，默认 utf-8；服务端改成 GBK 时这里也要填 gbk")
    parser.add_argument("--hex", action="store_true", help="打印收到的原始十六进制字节")
    return parser


def send_line(sock, text, lock, charset):
    with lock:
        # 解码时用了 errors="replace" 可能产生 U+FFFD，先转成 '?' 再编码，
        # 否则回显乱码内容时会抛 UnicodeEncodeError（例如 gbk 编不了 \ufffd）
        safe = text.encode(charset, errors="replace").decode(charset, errors="replace")
        sock.sendall((safe + "\r\n").encode(charset))
    print("  → 发送: {0}".format(safe), flush=True)


def heartbeat_loop(sock, lock, stop_event, session_id, charset):
    while not stop_event.wait(HEARTBEAT_SECONDS):
        try:
            send_line(sock, json.dumps({
                "type": "hb",
                "id": session_id,
                "uptime": int(time.time()) % 100000
            }, ensure_ascii=False), lock, charset)
        except OSError as exc:
            print("  ! 心跳发送失败: {0}".format(exc), flush=True)
            stop_event.set()
            return


def main():
    args = build_parser().parse_args()
    session_id = "sim-{0}".format(int(time.time()) % 100000)

    print("=" * 60)
    print(" ESP8266 模拟器启动")
    print(" 目标服务端 : {0}:{1}".format(args.host, args.port))
    print(" 设备标识   : {0}".format(args.name))
    print(" 心跳       : {0}".format("关闭" if args.no_heartbeat else "{0}s".format(HEARTBEAT_SECONDS)))
    print(" 收发字符集 : {0}".format(args.charset))
    print("=" * 60)

    try:
        sock = socket.create_connection((args.host, args.port), timeout=10)
    except OSError as exc:
        print("[错误] 无法连接服务端 {0}:{1} -> {2}".format(args.host, args.port, exc))
        print("       请确认服务端已启动（mvn spring-boot:run 或 java -jar），且端口未被防火墙拦截。")
        return 1

    sock.settimeout(None)
    lock = threading.Lock()
    stop_event = threading.Event()

    print("[OK] 已连接到服务端，开始收发数据\n")

    # 1) 上线握手：约定用 {"type":"hello"}，服务端会把它原样显示在日志里
    send_line(sock, json.dumps({
        "type": "hello",
        "id": session_id,
        "name": args.name,
        "ip": sock.getsockname()[0],
        "fw": "simulator-1.0"
    }, ensure_ascii=False), lock, args.charset)

    # 2) 心跳线程
    if not args.no_heartbeat:
        threading.Thread(target=heartbeat_loop,
                         args=(sock, lock, stop_event, session_id, args.charset),
                         daemon=True).start()

    # 3) 定时断线（演示离线检测）
    if args.drop_after > 0:
        def dropper():
            time.sleep(args.drop_after)
            print("\n[模拟] {0} 秒到，主动断开连接".format(args.drop_after), flush=True)
            stop_event.set()
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        threading.Thread(target=dropper, daemon=True).start()

    # 4) 主循环：按行接收服务端下发的数据
    buffer = b""
    try:
        while not stop_event.is_set():
            chunk = sock.recv(1024)
            if not chunk:
                print("\n[断开] 服务端关闭了连接")
                break
            buffer += chunk
            while b"\n" in buffer:
                raw, buffer = buffer.split(b"\n", 1)
                if args.hex:
                    print("  [HEX] {0}".format(" ".join("%02X" % b for b in raw)), flush=True)
                line = raw.decode(args.charset, errors="replace").strip()
                if not line:
                    continue
                print("  ← 收到: {0}".format(line), flush=True)
                handle_command(sock, lock, line, args.charset)
    except KeyboardInterrupt:
        print("\n[退出] 收到 Ctrl+C")
    except OSError as exc:
        print("\n[断开] 连接异常: {0}".format(exc))
    finally:
        stop_event.set()
        try:
            sock.close()
        except OSError:
            pass
        print("[结束] 模拟器已停止")
    return 0


def handle_command(sock, lock, line, charset):
    """像真实单片机那样对指令做出反应，便于在浏览器日志里看到闭环。"""
    upper = line.upper()
    if upper == "LED_ON":
        send_line(sock, "ACK:LED_ON,LED=1", lock, charset)
    elif upper == "LED_OFF":
        send_line(sock, "ACK:LED_OFF,LED=0", lock, charset)
    elif upper in ("STATUS?", "STATUS"):
        send_line(sock, json.dumps({
            "type": "status",
            "led": 0,
            "uart": "idle",
            "rssi": -58,
            "heap": 41200
        }, ensure_ascii=False), lock, charset)
    elif upper.startswith("BEEP"):
        send_line(sock, "ACK:{0}".format(upper), lock, charset)
    elif upper == "RESET":
        send_line(sock, "ACK:RESET,rebooting...", lock, charset)
    else:
        # 回显收到的内容（含中文），这样能验证服务端 -> 模拟器的编码是否正确
        send_line(sock, "ACK:{0}".format(line), lock, charset)


if __name__ == "__main__":
    sys.exit(main())
