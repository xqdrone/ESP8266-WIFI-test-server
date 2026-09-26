#!/usr/bin/env bash
# ============================================================
#  ESP8266 WiFi / TCP 测试服务端 —— 启动脚本（Linux / macOS）
#
#  配置集中在本文件顶部，按需修改。
# ============================================================

# ---------- 可编辑配置 ----------
WEB_PORT=8080
TCP_PORT=9000
HEARTBEAT_MS=15000
# --------------------------------

set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

command -v mvn  >/dev/null 2>&1 || { echo "[错误] 未找到 mvn，请先安装 Maven"; exit 1; }
command -v java >/dev/null 2>&1 || { echo "[错误] 未找到 java，请先安装 JDK 17+"; exit 1; }

echo "[1/3] 编译源码..."
mvn -o -B -q compile

echo "[2/3] 复制运行时依赖到 target/lib ..."
mvn -o -B -q dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime

echo "[3/3] 启动服务端..."
echo
echo "  浏览器控制台 : http://127.0.0.1:$WEB_PORT"
echo "  单片机 TCP   : 本机局域网IP:$TCP_PORT   (启动日志会打印可用 IP)"
echo "  停止服务     : 按 Ctrl+C"
echo

exec java -cp "target/classes:target/lib/*" \
     -Dserver.port="$WEB_PORT" \
     -Dapp.tcp.port="$TCP_PORT" \
     -Dapp.heartbeat-timeout-millis="$HEARTBEAT_MS" \
     com.example.esp8266.Esp8266TcpServerApplication
