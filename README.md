# ESP8266 WiFi / TCP 测试台

一个本地运行的服务端 + 浏览器可视化控制台，用来测试 **ESP8266 的 WiFi + TCP/IP 通信**。

- 服务端挂在 PC 本地，单片机与 PC 连同一个 WiFi，通过裸 TCP 与服务端通信；
- 浏览器打开控制台即可看到**连接状态**、**收发日志**，并点击**可编辑按钮**下发指定数据；
- 附带设备模拟器，没有硬件也能先把整条链路跑通。

---

## 1. 快速开始

### 方式 A：一键脚本（推荐）

```bat
:: Windows —— 双击运行，或命令行执行
run.bat
```

```bash
# Linux / macOS
chmod +x run.sh && ./run.sh
```

脚本会自动编译、解析依赖并启动服务端。启动日志类似：

```
 ESP8266 TCP 服务端已启动，监听端口: 9000
 单片机请连接 -> 192.168.10.149:9000
 单片机请连接 -> 192.168.137.1:9000
 浏览器控制台 -> http://127.0.0.1:8080
```

浏览器打开 **http://127.0.0.1:8080** 即可。

> 日志里会打印本机所有可用 IP，界面顶部也会显示，点「复制」就能拿到要写进单片机代码的地址。
> 请选用与手机/开发板同一个网段的那个（通常是 `192.168.x.x`）。

### 方式 B：打包成可执行 jar

```powershell
powershell -ExecutionPolicy Bypass -File .\package.ps1
java -jar target\esp8266-tcp-server.jar
```

产物是 `target\esp8266-tcp-server.jar` **加上同级的 `target\lib\` 目录**，两者必须放在一起：

```
target\
├── esp8266-tcp-server.jar     ← 只有 40 KB 左右
└── lib\                        ← 35 个依赖 jar
```

要移动到别处时请把 `lib\` 一起带上。

### 方式 C：纯 Maven 命令

```bash
mvn -o -B compile
mvn -o -B dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime
java -cp "target/classes;target/lib/*" com.example.esp8266.Esp8266TcpServerApplication
```

（Linux / macOS 把 `;` 换成 `:`）

### 没有硬件？用模拟器

另开一个终端：

```bat
tools\simulate.bat
:: 或
python tools\device-simulator.py 127.0.0.1 9000
```

模拟器会像真实单片机一样连上服务端、发上线报文和心跳，并对 `LED_ON` / `STATUS?` 等指令回 ACK。
此时在浏览器里点按钮，就能在两侧同时看到数据往返。

---

## 2. 界面说明

| 区域 | 功能 |
| --- | --- |
| 顶栏 | 服务端连接状态、单片机接入地址（可复制）、在线设备数、下发目标选择 |
| 左侧「设备 / 连接状态」 | 每个设备的在线状态徽标、远端地址、已连接时长、收发条数、最近数据时间、断开按钮 |
| 左侧「数据日志」 | 单片机上报（`←`）、界面下发（`→`）、系统事件（`•`）实时滚动，可清空/暂停自动滚动 |
| 右侧「控制按钮」 | 点击即发送；悬停右上角 `✎` 可编辑；支持新增/删除/改色/恢复默认 |
| 右侧底部 | 自定义内容发送框，支持回车发送，例如 `LED_ON` 或 `{"cmd":"led","value":1}` |

- 按钮配置保存在浏览器 `localStorage`，改完刷新不丢；
- 「下发目标」可选**全部在线设备（广播）**或某一台设备；
- 按钮里的数据**原样下发**，服务端自动补 `CRLF`，单片机按行读取即可。

---

## 3. 通信协议

极简的**行文本协议**，方便在单片机上实现：

```
服务端 -> 单片机 :   <数据>\r\n
单片机 -> 服务端 :   <数据>\r\n      （\n 单独结尾也可以）
```

- 一条消息一行，`\r\n` 或 `\n` 结尾；
- 内容不做强制约束：纯文本（`LED_ON`）或 JSON（`{"cmd":"led","value":1}`）都可以；
- 服务端会原样转发给浏览器并按设备记录最近 50 条历史。

**建议单片机定时发心跳**（示例每 5 秒一次），否则超过 `app.heartbeat-timeout-millis`
（默认 15 秒）没有数据，服务端会判定离线并断开，界面上显示「超时/离线」。

推荐的报文（非强制，纯文本也可以）：

```jsonc
// 上线
{"type":"hello","name":"esp8266-01","ip":"192.168.1.50","fw":"1.0"}
// 心跳
{"type":"hb","name":"esp8266-01","uptime":123,"rssi":-58}
// 应答
{"type":"ack","cmd":"LED_ON","value":1}
```

### 浏览器 <-> 服务端（WebSocket `ws://<host>:8080/ws`）

```jsonc
{"cmd":"send","data":"LED_ON","target":"all"}       // 广播
{"cmd":"send","data":"LED_ON","target":"dev-1"}     // 指定设备
{"cmd":"disconnect","target":"dev-1"}               // 断开设备
{"cmd":"set-charset","charset":"GBK"}               // 实时切换下发编码
{"cmd":"ping"}                                      // 保活
```

### 备用 REST 接口

```bash
curl http://127.0.0.1:8080/api/health              # 健康检查
curl http://127.0.0.1:8080/api/state               # 全部设备状态
curl -X POST http://127.0.0.1:8080/api/send \
     -H "Content-Type: application/json" \
     -d '{"data":"LED_ON"}'                        # 广播下发
curl -X POST "http://127.0.0.1:8080/api/send?target=dev-1" \
     -H "Content-Type: application/json" -d '{"data":"STATUS?"}'
curl -X DELETE http://127.0.0.1:8080/api/devices/dev-1   # 断开设备
curl -X POST "http://127.0.0.1:8080/api/charset?name=GBK" # 切换下发编码（立即生效并保存）
```

---

## 4. 中文乱码排查（重点）

服务端下发的文本默认按 **UTF-8** 编码成字节。**发送中文出现乱码，99% 是字符集不一致**，
而不是网络或硬件问题 —— 字节本身完全正确，只是被接收端用错误的编码解释了。

### 4.1 先确认字节（唯一可靠的判断依据）

临时打开十六进制日志：

```bash
java -jar target/esp8266-tcp-server.jar -Dapp.hex-dump-on-send=true -Dapp.send-charset=GBK
```

下发中文后，服务端控制台会打印：

```
[广播] -> 打开客厅灯 (10 字节, GBK): B4 F2 BF AA BF CD CC FC B5 C6
```

同一句 `打开客厅灯` 在两种编码下的字节完全不同：

| 编码 | 字节（十六进制） | 长度 |
| --- | --- | --- |
| UTF-8 | `E6 89 93 E5 BC 80 E5 AE A2 E5 8E 85 E7 81 AF` | 15 字节（3 字节/汉字） |
| GBK | `B4 F2 BF AA BF CD CC FC B5 C6` | 10 字节（2 字节/汉字） |

如果串口助手里显示 `鎵撳紑瀹㈠巺鐏`，用上表的 UTF-8 字节去对照就能确认：
**发的是 UTF-8，收的一方按 GBK 解的**。

### 4.2 解决办法：让服务端发 GBK

中文 Windows 的串口助手默认按 GBK 解码，STM32 上常见的 LCD/OLED 字库也是 GBK。
所以最省事的做法是让服务端直接发 GBK 字节 —— **单片机侧一行代码都不用改**。

**推荐：直接在界面上点一下。** 顶栏的「下发编码」是一个下拉按钮，
点开选中 `GBK` 即可：

- 切换 **立即生效**，已经连着的设备不用重连、不用插拔；
- 自动保存到配置文件，**重启后依然保持**（不会再变回 UTF-8）；
- 顶栏会一直显示当前编码，非 UTF-8 时高亮提醒。

也可以改配置文件作为“出厂默认”（界面没切换过时以它为准）：

```properties
# src/main/resources/application.properties
app.send-charset=GBK
```

或者启动时临时指定：

```bat
:: cmd 里这样写没问题
java -jar target\esp8266-tcp-server.jar -Dapp.send-charset=GBK
```

```powershell
# PowerShell 下 -D 开头的参数会被 PowerShell 自己吞掉，请改用环境变量：
$env:APP_SENDCHARSET='GBK'
java -jar target\esp8266-tcp-server.jar
```

Spring Boot 会自动把 `APP_SENDCHARSET` 绑定到 `app.send-charset`
（规则：前缀大写 + `.` 和 `-` 去掉），所以 `APP_HEXDUMPONSEND=true` 就对应
`app.hex-dump-on-send=true`。

#### 优先级顺序

界面切换过的值会写进 `esp8266-console.properties`，**它优先级最高**：

```
界面保存的 esp8266-console.properties
        ↓ 没有该文件时
app.send-charset / APP_SENDCHARSET
        ↓ 都没设时
UTF-8
```

所以如果你发现「改了 application.properties 但没生效」，多半是这个文件在压着。
删掉 `esp8266-console.properties` 即可恢复。

支持的值：`UTF-8`、`GBK`、`GB2312`、`GB18030`、`BIG5`、`UTF-16LE`、`ISO-8859-1`
（界面上只列出当前 JVM 真正支持的）。

> 只影响**服务端 → 单片机**的方向。单片机**上报**的数据默认按 UTF-8 解码；
> 如果它按 GBK 回传中文（表现为服务端日志里出现 `ACK:ï¿½ï¿½ï¿½` 这类乱码），
> 把 `app.receive-charset` 设为 `GBK` 即可：

```properties
app.receive-charset=GBK   # 单片机也用 GBK 回传中文时
```

启动日志会同时打印两个方向的实际编码，方便核对：

```
 下发编码     -> GBK  (来源: 界面保存 (esp8266-console.properties)；界面上可实时切换)
 接收编码     -> GBK  (app.receive-charset，单片机上报中文乱码时设为 GBK)
```

### 4.3 如果你更想自己控制：STM32 侧做转换

也可以保持服务端发 UTF-8，在 STM32 上把 UTF-8 转成 GBK 再送 LCD。
但要注意 STM32 上做这件事**必须带一张 UTF-8→GBK 映射表**（常见汉字约 2 万条，
`uint16_t` 表约 40 KB），对 F103C8T6 这种 64 KB Flash 的片子是很大的开销。
除非你确实需要同时支持多语言，否则建议直接用 4.2 的方案。

### 4.4 串口助手侧的设置

如果只是**你自己在 PC 上看**乱码，而单片机业务逻辑并不关心编码，
那更简单：把串口助手的编码从 GBK 改成 **UTF-8** 即可。
常见工具的设置位置：编码/字符集下拉框 → 选 `UTF-8`（有的写作 `UTF8`、`Unicode`）。
注意这种方式只解决「显示」，STM32 送给 LCD 的仍然是 UTF-8 字节。

### 4.5 AT 固件模式下的字节数（很容易踩）

用 STM32 通过 AT 指令驱动 ESP8266 时，这一条务必注意：

```c
// AT+CIPSEND=<长度> 里的长度是【字节数】，不是字符数！
// 而且必须把自己要发的 \r\n 一起算进去
// "打开客厅灯" 按 GBK 是 10 字节
AT_SendCmd("AT+CIPSEND=12\r\n");   // 10 (数据) + 2 (\r\n)
// 等到 '>' 提示符出现后，再发 10 字节数据 + "\r\n"
```

- 汉字按 **GBK 是 2 字节**、按 **UTF-8 是 3 字节**，一定要用 `strlen()`（字节长度），
  不要自己数汉字个数；
- **长度算少**：数据被截断，剩余部分混进下一次发送，表现为「粘包 / 丢字」；
- **长度算多**：ESP8266 会一直等剩余字节，表现为「卡住不发」；
- 如果长度恰好把 `\r` 发过去而 `\n` 留在下一次，服务端就收不到完整的一行。

建议在 STM32 上统一封装一个函数，避免到处手算：

```c
void TcpSendLine(const char *text) {
    uint16_t len = (uint16_t)strlen(text);        // 字节长度，不是汉字个数
    char cmd[24];
    sprintf(cmd, "AT+CIPSEND=%u\r\n", (unsigned)(len + 2));   // +2 是 \r\n
    if (AT_WaitResponse(cmd, ">", 1000) == AT_OK) {
        HAL_UART_Transmit(&huart2, (uint8_t *)text, len, 1000);
        HAL_UART_Transmit(&huart2, (uint8_t *)"\r\n", 2, 1000);
    }
}
```

### 4.6 快速自检

用模拟器验证服务端编码是否正确（`--charset` 要与 `app.send-charset` 一致）：

```bash
# 服务端设成 GBK 时
python tools/device-simulator.py 127.0.0.1 9000 --charset gbk --hex
```

正常会看到：

```
[HEX] B4 F2 BF AA BF CD CC FC B5 C6 0D
← 收到: 打开客厅灯
```

若 `--charset` 用错，就会看到 `鎵撳紑瀹㈠巺鐏` 这类乱码 —— 这正好可以反向确认问题所在。

---

## 5. 接入真实硬件

`examples/ESP8266_TcpClient/ESP8266_TcpClient.ino` 是完整的 Arduino 示例
（依赖 `ESP8266WiFi` + `ArduinoJson`）。修改顶部四处配置后烧录：

```cpp
const char* WIFI_SSID   = "YOUR_WIFI_SSID";    // 与 PC 同一个 WiFi
const char* WIFI_PASS   = "YOUR_WIFI_PASSWORD";
const char* SERVER_HOST = "192.168.1.100";     // PC 的局域网 IP（界面顶部可复制）
const uint16_t SERVER_PORT = 9000;             // 对应 app.tcp.port
```

示例已实现：WiFi 连接、TCP 连接与断线重连、上线报文、5 秒心跳、指令解析与 ACK。

### 与 STM32 配合的两种方式

**方式 1（推荐）：ESP8266 负责联网，STM32 负责业务**

ESP8266 烧录上面的示例，通过 UART 与 STM32 互发**按行**的数据；示例文件末尾给出了
`forwardToStm32()` / `pumpStm32()` 的桥接代码片段。PC 下发的数据经服务端 -> ESP8266 ->
STM32，STM32 的结果再原路返回。ESP8266 在这里相当于一个「串口转 TCP 透传模块」。

**方式 2：STM32 用 AT 指令驱动 ESP8266**

ESP8266 保持出厂 AT 固件，STM32 发 AT 指令组网并建连，关键顺序：

```
AT+CWMODE=1                          // Station 模式
AT+CWJAP="SSID","PASSWORD"           // 连接同一个 WiFi
AT+CIPMUX=0                          // 单连接
AT+CIPSTART="TCP","192.168.1.100",9000
AT+CIPSEND=<n>                       // 先报长度，收到 '>' 后再发 n 字节数据
```

要点：
- 发送数据长度 `n` **必须包含结尾的 `\r\n`**，否则服务端不认为一行结束；
- 建议每秒查询一次 `AT+CIPSTATUS` 或在收到 `CLOSED` 时重连；
- 心跳数据同样通过 `AT+CIPSEND` 定时发送。

---

## 6. 配置项

集中在 `src/main/resources/application.properties`：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8080` | 浏览器控制台端口 |
| `app.tcp.port` | `9000` | 单片机连接的 TCP 端口 |
| `app.heartbeat-timeout-millis` | `15000` | 多久没收到数据判定离线；不做心跳可调大 |
| `app.tcp.handshake-timeout-millis` | `20000` | 建连后等待首条数据的超时 |
| `app.max-history-per-device` | `50` | 每台设备保留的历史消息条数 |
| `app.max-work-log-lines` | `500` | 界面日志行数上限 |
| `app.echo-to-console` | `true` | 是否在服务端控制台打印收到的数据 |
| `app.send-charset` | `UTF-8` | **向单片机发送数据的编码**；中文乱码时改成 `GBK`（也可在界面顶栏点击切换，详见第 4 节） |
| `app.charset-config-file` | `esp8266-console.properties` | 界面切换编码后保存到的文件；删掉它即恢复上面的默认值 |
| `app.receive-charset` | `UTF-8` | 解码**单片机上报**数据用的编码；上报中文乱码时设为 `GBK` |
| `app.hex-dump-on-send` | `false` | 下发时打印十六进制字节，排查编码问题用 |

启动时也可以用 `-D` 覆盖，`run.bat` 顶部就提供了 `WEB_PORT` / `TCP_PORT` / `HEARTBEAT_MS` 三个变量：

```bash
java -jar target/esp8266-tcp-server.jar -Dapp.tcp.port=9100 -Dserver.port=8090
```

---

## 7. 工程结构

```
esp8266-tcp-server/
├── pom.xml                       Maven 工程（Spring Boot 2.7.3 / Java 17）
├── run.bat / run.sh              编译 + 启动
├── package.ps1                   离线打包成可执行 jar（可选）
├── src/main/java/com/example/esp8266/
│   ├── Esp8266TcpServerApplication.java   入口，@EnableScheduling
│   ├── Launcher.java                      打包后的 jar 入口，负责加载 lib/*.jar
│   ├── core/
│   │   ├── DeviceHub.java                 核心：TCP 监听、收发、心跳巡检、向浏览器广播
│   │   ├── DeviceConnection.java          单条设备连接（按行读写、历史、计数）
│   │   ├── AppProperties.java             配置绑定（app.*）
│   │   └── JsonWriter.java                极简 JSON 序列化
│   ├── web/
│   │   ├── ApiController.java             备用 REST 接口
│   │   ├── DeviceWebSocketHandler.java    /ws 指令处理
│   │   ├── ParsedCommand.java             浏览器 JSON 指令解析
│   │   └── WebSocketConfig.java           注册 /ws
│   └── util/NetUtils.java                 枚举本机局域网 IP
├── src/main/resources/
│   ├── application.properties    配置文件
│   └── static/index.html         浏览器控制台（单文件，无外部依赖）
├── examples/ESP8266_TcpClient/   Arduino 示例
└── tools/
    ├── device-simulator.py       单片机模拟器
    ├── simulate.bat              Windows 启动模拟器
    ├── ws-smoke-test.py          WebSocket 链路自检
    └── heartbeat-timeout-test.py 心跳超时（离线判定）自检
```

数据流：

```
浏览器(8080)  ──WebSocket──►  DeviceHub  ──TCP(9000)──►  ESP8266 / 单片机
     ▲                            │
     └────────── 状态/日志推送 ────┘
```

---

## 8. 离线构建说明

本工程可以在**完全没有网络**的机器上构建，前提是本地 Maven 仓库里已有 Spring Boot 2.7.3 相关依赖。

需要注意的是：`maven-jar-plugin` 自身的依赖（`maven-archiver`、`plexus-archiver` 等）
在离线仓库里常常只有 `.pom` 没有 `.jar`，导致 `mvn -o package` 失败。因此：

- **日常开发用 `run.bat` / `run.sh`**：只走 `compile` + `dependency:copy-dependencies`，
  再用 `target\lib\*` 通配符组装 classpath，全程不依赖打包插件；
- **需要独立 jar 时用 `package.ps1`**：改用 JDK 自带的 `java` / `jar` 命令组装，
  主类为 `Launcher`，由它在运行时加载 `lib/*.jar`，从而绕开 MANIFEST 每行 72 字节的限制。

如果本机可以联网，去掉命令里的 `-o` 即可走标准 Maven 流程（也可自行加回
`spring-boot-maven-plugin` 打成 fat jar）。

### 两个已经踩过的坑（改脚本时请注意）

1. `.bat` 文件必须是 **CRLF 换行 + 纯 ASCII**。LF 换行或 UTF-8 中文会让 `cmd.exe`
   把文件拆错行，报出一堆 `'xxx' is not recognized as an internal or external command`。
2. 不要用 `set /p CP=<classpath.txt` 来读取 Maven 生成的 classpath：
   那是一个约 3.6 KB 的**单行**文件，而 `cmd` 的 `set /p` 在 1023 字符处会**静默截断**，
   结果是启动时报 `NoClassDefFoundError: org/springframework/...`。
   请改用 `target\lib\*` 通配符。

---

## 9. 常见问题

| 现象 | 排查方向 |
| --- | --- |
| 界面显示「服务端已断开」 | 服务端没启动或换过端口；确认 `http://127.0.0.1:8080` 可访问 |
| 设备一直不出现 | 单片机填的 IP 不对（要填 PC 局域网 IP，不是 `127.0.0.1`）；或 Windows 防火墙拦了 9000 端口 |
| 端口被占用启动失败 | 改 `app.tcp.port` / `server.port`，启动日志会打印具体错误 |
| 设备刚连上就掉线 | 单片机建连后 20 秒内没有发数据（`app.tcp.handshake-timeout-millis`） |
| 设备显示「超时/离线」 | 单片机没有定时发心跳，或 `app.heartbeat-timeout-millis` 设得太小 |
| 单片机收到乱码 | ESP8266 侧按行读取（`readStringUntil('\n')`），并确保波特率一致 |
| **发中文显示乱码** | **先看第 4 节**：把 `app.send-charset` 改成 `GBK`；用 `-Dapp.hex-dump-on-send=true` 看实际字节 |
| 点击按钮无反应 | 顶栏「下发目标」选的设备已离线；日志区会提示「当前没有在线设备」 |
| 想让别的电脑也能访问 | 服务端默认监听 `0.0.0.0`，用 PC 的局域网 IP 访问 `http://192.168.x.x:8080` |

Windows 防火墙放行 9000 端口（管理员 PowerShell）：

```powershell
New-NetFirewallRule -DisplayName "ESP8266 TCP 9000" -Direction Inbound -Protocol TCP -LocalPort 9000 -Action Allow
```

---

## 10. 自检脚本

在服务端已启动的前提下：

```bash
python tools/ws-smoke-test.py 127.0.0.1 8080          # 验证 WebSocket 与下发链路
python tools/heartbeat-timeout-test.py 127.0.0.1 9000 30   # 验证心跳超时离线判定
```

两个脚本都只用 Python 标准库，无需安装任何依赖。
