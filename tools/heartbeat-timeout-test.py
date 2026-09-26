#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
心跳超时（离线判定）测试。

连上服务端但不发任何数据，观察服务端是否在 app.heartbeat-timeout-millis
之后把该设备标记为离线并断开。

用法：
  python heartbeat-timeout-test.py [host] [port] [等待秒数]
"""

import socket
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):
    pass


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 9000
    hold = float(sys.argv[3]) if len(sys.argv) > 3 else 30.0

    sock = socket.create_connection((host, port), timeout=10)
    print("[OK] 已连接 {0}:{1}，故意不发送任何数据".format(host, port))
    print("[..] 保持连接 {0} 秒，服务端应在心跳超时后主动断开...".format(hold))

    sock.settimeout(hold + 10)
    start = time.time()
    try:
        data = sock.recv(1024)
        elapsed = time.time() - start
        if not data:
            print("[OK] 服务端已断开该连接，用时 {0:.1f} 秒（心跳超时判定生效）".format(elapsed))
            return 0
        print("[??] 收到意外数据: {0!r}".format(data))
        return 1
    except socket.timeout:
        print("[失败] {0} 秒内服务端未断开连接，请检查 app.heartbeat-timeout-millis 配置".format(hold))
        return 1
    finally:
        sock.close()


if __name__ == "__main__":
    sys.exit(main())
