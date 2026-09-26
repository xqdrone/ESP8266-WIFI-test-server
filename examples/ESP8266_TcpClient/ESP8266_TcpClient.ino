/*
 * ============================================================================
 *  ESP8266 -> PC 服务端 TCP 通信示例
 * ============================================================================
 *  配套工程: esp8266-tcp-server（本仓库的 Java 服务端）
 *
 *  运行前请修改下面 WIFI_SSID / WIFI_PASS / SERVER_HOST / SERVER_PORT。
 *  SERVER_HOST 填 PC 的局域网 IP —— 启动 Java 服务端时控制台会打印，
 *  浏览器界面顶部「单片机接入」那一栏也会显示，点「复制」即可。
 *
 *  协议（与 DeviceHub.java 对应）：
 *    - 一条消息一行，以 \r\n 或 \n 结尾；
 *    - 内容可以是纯文本（LED_ON）或 JSON（{"cmd":"led","value":1}）；
 *    - 服务端下发的数据同理，末尾会自动补 CRLF；
 *    - 建议每 5 秒发一次心跳，否则服务端会在 app.heartbeat-timeout-millis
 *      之后把本设备标记为离线（默认 15 秒）。
 *
 *  依赖：ArduinoJson（库管理器搜索安装，v6 或 v7 均可）
 * ============================================================================
 */

#include <ESP8266WiFi.h>
#include <ArduinoJson.h>

// ------------------------------ 用户配置 ------------------------------
const char* WIFI_SSID = "YOUR_WIFI_SSID";      // 与 PC 连同一个 WiFi
const char* WIFI_PASS = "YOUR_WIFI_PASSWORD";

const char* SERVER_HOST = "192.168.1.100";     // PC 的局域网 IP
const uint16_t SERVER_PORT = 9000;             // 对应 app.tcp.port

const char* DEVICE_NAME = "esp8266-01";

const unsigned long HEARTBEAT_INTERVAL_MS = 5000;   // 心跳间隔
const unsigned long RECONNECT_INTERVAL_MS = 3000;   // 断线重连间隔

#ifdef LED_BUILTIN
const uint8_t LED_PIN = LED_BUILTIN;           // NodeMCU 上通常是 GPIO2，低电平点亮
#else
const uint8_t LED_PIN = 2;
#endif
const uint8_t LED_ACTIVE_LEVEL = LOW;          // 板载 LED 多为低电平点亮
// ---------------------------------------------------------------------

WiFiClient client;

unsigned long lastHeartbeat = 0;
unsigned long lastReconnect = 0;
String rxBuffer;

// ---------------------------------------------------------------------
//  发送
// ---------------------------------------------------------------------
void sendLine(const String& line) {
  if (!client.connected()) {
    return;
  }
  client.print(line);
  client.print("\r\n");          // 服务端按行解析，必须有换行
  Serial.print("[TX] ");
  Serial.println(line);
}

void sendHello() {
  StaticJsonDocument<192> doc;
  doc["type"] = "hello";
  doc["name"] = DEVICE_NAME;
  doc["ip"] = WiFi.localIP().toString();
  doc["rssi"] = WiFi.RSSI();
  doc["fw"] = "esp8266-arduino-1.0";

  String out;
  serializeJson(doc, out);
  sendLine(out);
}

void sendHeartbeat() {
  StaticJsonDocument<160> doc;
  doc["type"] = "hb";
  doc["name"] = DEVICE_NAME;
  doc["uptime"] = millis() / 1000;
  doc["rssi"] = WiFi.RSSI();
  doc["heap"] = ESP.getFreeHeap();

  String out;
  serializeJson(doc, out);
  sendLine(out);
}

void sendAck(const char* what, int value) {
  StaticJsonDocument<160> doc;
  doc["type"] = "ack";
  doc["cmd"] = what;
  doc["value"] = value;
  doc["name"] = DEVICE_NAME;

  String out;
  serializeJson(doc, out);
  sendLine(out);
}

// ---------------------------------------------------------------------
//  处理服务端下发的指令
// ---------------------------------------------------------------------
void handleCommand(String line) {
  line.trim();
  if (line.isEmpty()) {
    return;
  }

  // 1) 先尝试按 JSON 解析（{"cmd":"led","value":1}）
  if (line.startsWith("{")) {
    StaticJsonDocument<256> doc;
    DeserializationError err = deserializeJson(doc, line);
    if (!err) {
      const char* cmd = doc["cmd"] | "";
      if (strcmp(cmd, "led") == 0) {
        int value = doc["value"] | 0;
        digitalWrite(LED_PIN, value ? LED_ACTIVE_LEVEL : !LED_ACTIVE_LEVEL);
        sendAck("led", value);
        return;
      }
      if (strcmp(cmd, "status") == 0) {
        sendHeartbeat();
        return;
      }
      if (strcmp(cmd, "reset") == 0) {
        sendAck("reset", 1);
        delay(100);
        ESP.restart();
        return;
      }
      sendAck("unknown", 0);
      return;
    }
  }

  // 2) 纯文本指令
  String upper = line;
  upper.toUpperCase();

  if (upper == "LED_ON") {
    digitalWrite(LED_PIN, LED_ACTIVE_LEVEL);
    sendAck("LED_ON", 1);
  } else if (upper == "LED_OFF") {
    digitalWrite(LED_PIN, !LED_ACTIVE_LEVEL);
    sendAck("LED_OFF", 0);
  } else if (upper == "STATUS?" || upper == "STATUS") {
    sendHeartbeat();
  } else if (upper == "RESET") {
    sendAck("RESET", 1);
    delay(100);
    ESP.restart();
  } else {
    // 未识别：原样回一条，方便在浏览器日志里确认链路通了
    sendLine("ACK:" + line);
  }
}

// ---------------------------------------------------------------------
//  网络
// ---------------------------------------------------------------------
void connectWiFi() {
  if (WiFi.status() == WL_CONNECTED) {
    return;
  }
  Serial.printf("[WiFi] 正在连接 %s ...\n", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 20000) {
    delay(400);
    Serial.print('.');
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("[WiFi] 已连接，本机 IP: ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("[WiFi] 连接失败，稍后重试");
  }
}

void connectServer() {
  if (client.connected()) {
    return;
  }
  if (WiFi.status() != WL_CONNECTED) {
    return;
  }

  Serial.printf("[TCP] 连接服务端 %s:%u ...\n", SERVER_HOST, SERVER_PORT);
  if (client.connect(SERVER_HOST, SERVER_PORT)) {
    Serial.println("[TCP] 连接成功");
    rxBuffer = "";
    sendHello();
    lastHeartbeat = millis();
  } else {
    Serial.println("[TCP] 连接失败，稍后重试");
  }
}

void readFromServer() {
  while (client.available()) {
    char c = static_cast<char>(client.read());
    if (c == '\n') {
      handleCommand(rxBuffer);
      rxBuffer = "";
    } else if (c != '\r') {
      if (rxBuffer.length() < 512) {   // 防止异常数据把内存吃满
        rxBuffer += c;
      }
    }
  }
}

// ---------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  delay(200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, !LED_ACTIVE_LEVEL);

  Serial.println();
  Serial.println("========== ESP8266 TCP 客户端示例 ==========");
  Serial.printf("目标服务端: %s:%u\n", SERVER_HOST, SERVER_PORT);

  connectWiFi();
  connectServer();
}

void loop() {
  // WiFi 掉线重连
  if (WiFi.status() != WL_CONNECTED) {
    connectWiFi();
  }

  // TCP 断线重连
  if (!client.connected()) {
    if (millis() - lastReconnect > RECONNECT_INTERVAL_MS) {
      lastReconnect = millis();
      connectServer();
    }
    return;
  }

  // 收数据
  readFromServer();

  // 定时心跳
  if (millis() - lastHeartbeat > HEARTBEAT_INTERVAL_MS) {
    lastHeartbeat = millis();
    sendHeartbeat();
  }
}

/*
 * ============================================================================
 *  扩展：把 PC 下发的指令转发给 STM32（串口透传）
 * ============================================================================
 *  如果 ESP8266 只做“WiFi 模块”，业务逻辑在 STM32 上，可以这样桥接：
 *
 *  - 收到服务端数据后，通过 Serial（接 STM32 的 UART）转发：
 *
 *        void forwardToStm32(const String& line) {
 *          Serial.print(line);
 *          Serial.print('\n');       // STM32 侧按行解析
 *        }
 *
 *  - STM32 处理完把结果写回串口，ESP8266 再转发给服务端：
 *
 *        void pumpStm32() {
 *          while (Serial.available()) {
 *            String line = Serial.readStringUntil('\n');
 *            if (line.length()) sendLine(line);
 *          }
 *        }
 *
 *  注意：Serial 既是调试口也是通信口时，请先注释掉所有 Serial.print 调试输出，
 *  或者改用 Serial1 / SoftwareSerial 单独接 STM32，避免两条数据流相互干扰。
 * ============================================================================
 */
