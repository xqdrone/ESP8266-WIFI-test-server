package com.example.esp8266.core;

import com.example.esp8266.util.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 核心枢纽：
 * <ol>
 *   <li>监听裸 TCP 端口，接受 ESP8266 / 单片机的长连接；</li>
 *   <li>按行接收数据，记录历史并推送给所有浏览器；</li>
 *   <li>把浏览器发来的数据下发给指定设备或全部设备；</li>
 *   <li>周期性巡检心跳，超时判定离线。</li>
 * </ol>
 * 所有更改都通过 {@link #broadcastState()} 主动推送，浏览器端无需轮询。
 */
@Component
public class DeviceHub {

    private static final Logger log = LoggerFactory.getLogger(DeviceHub.class);

    /** 配置文件换行符，跟随当前平台，避免在 Linux 上写出怪文件。 */
    private static final String NL = System.lineSeparator();

    private final AppProperties properties;
    private final Map<String, DeviceConnection> devices = new ConcurrentHashMap<>();
    /** 编译好的静默（心跳）规则；为空表示不做任何静默，全部显示。 */
    private final List<Pattern> quietPatterns = new ArrayList<>();
    private final Map<String, WebSocketSession> browsers = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 向单片机发送数据时使用的字符集，由 app.send-charset 决定；界面上可随时热切换。 */
    private volatile Charset sendCharset = StandardCharsets.UTF_8;

    /** 字符集来源说明，显示在界面/日志里，便于判断“为什么是这个编码”。 */
    private volatile String charsetSource = "默认";

    /** 接收单片机数据时使用的字符集（启动时确定，由 app.receive-charset 决定）。 */
    private Charset receiveCharset = StandardCharsets.UTF_8;

    /** 界面切换过的编码会写进这个文件，重启后依然生效。 */
    private File charsetConfigFile;

    /**
     * 界面可选的字符集候选。用前会过滤掉当前 JVM 不支持的（例如某些精简 JRE 没有 GBK）。
     */
    private static final List<String> CHARSET_CANDIDATES =
            Arrays.asList("UTF-8", "GBK", "GB2312", "GB18030", "BIG5", "UTF-16LE", "ISO-8859-1");


    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService clientPool;
    private ScheduledExecutorService watchdog;

    public DeviceHub(AppProperties properties) {
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 生命周期

    @PostConstruct
    public void start() {
        resolveSendCharset();
        resolveQuietPatterns();

        // 接收字符集：单片机上报中文乱码时改成 GBK
        Charset parsedReceive = tryCharset(properties.getReceiveCharset());
        if (parsedReceive == null) {
            this.receiveCharset = StandardCharsets.UTF_8;
            if (properties.getReceiveCharset() != null && !properties.getReceiveCharset().trim().isEmpty()) {
                log.warn("app.receive-charset={} 无效，已回退为 UTF-8", properties.getReceiveCharset());
            }
        } else {
            this.receiveCharset = parsedReceive;
        }

        int port = properties.getTcp().getPort();
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            this.serverSocket = socket;
        } catch (IOException e) {
            lastError.set("TCP 端口 " + port + " 监听失败: " + e.getMessage());
            log.error("TCP 服务端启动失败，端口 {} 可能已被占用", port, e);
            return;
        }

        this.clientPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "esp8266-client");
            t.setDaemon(true);
            return t;
        });
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "esp8266-watchdog");
            t.setDaemon(true);
            return t;
        });
        this.watchdog.scheduleWithFixedDelay(this::checkHeartbeats, 3, 3, TimeUnit.SECONDS);

        this.acceptThread = new Thread(this::acceptLoop, "esp8266-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();

        running.set(true);
        log.info("=========================================================");
        log.info(" ESP8266 TCP 服务端已启动，监听端口: {}", port);
        for (String ip : NetUtils.localIpv4Addresses()) {
            log.info(" 单片机请连接 -> {}:{}", ip, port);
        }
        log.info(" 浏览器控制台 -> http://127.0.0.1:8080");
        log.info(" 下发编码     -> {}  (来源: {}；界面上可实时切换)", sendCharset.name(), charsetSource);
        log.info(" 接收编码     -> {}  (app.receive-charset，单片机上报中文乱码时设为 GBK)", receiveCharset.name());
        log.info("=========================================================");
    }

    // ------------------------------------------------------- 下发编码（可热切换）

    /**
     * 决定启动时用哪个字符集，优先级：
     * <ol>
     *   <li>界面切换后保存的配置文件（esp8266-console.properties）；</li>
     *   <li>app.send-charset / APP_SENDCHARSET（application.properties 或环境变量）；</li>
     *   <li>默认 UTF-8。</li>
     * </ol>
     */
    private void resolveSendCharset() {
        charsetConfigFile = new File(properties.getCharsetConfigFile());

        // 1) 界面保存过的选择优先，避免“重启后又变回 UTF-8”
        String saved = readSavedCharset();
        if (saved != null) {
            Charset parsed = tryCharset(saved);
            if (parsed != null) {
                this.sendCharset = parsed;
                this.charsetSource = "界面保存 (" + charsetConfigFile.getName() + ")";
                return;
            }
            log.warn("配置文件 {} 中的 charset={} 无效，忽略", charsetConfigFile, saved);
        }

        // 2) 显式配置（环境变量 / -D / application.properties）
        if (properties.getSendCharset() != null && !properties.getSendCharset().trim().isEmpty()) {
            Charset parsed = tryCharset(properties.getSendCharset().trim());
            if (parsed != null) {
                this.sendCharset = parsed;
                this.charsetSource = "app.send-charset";
                return;
            }
            log.warn("app.send-charset={} 不是有效的字符集名，回退为 UTF-8", properties.getSendCharset());
            this.charsetSource = "默认（原配置无效）";
            return;
        }

        // 3) 默认
        this.sendCharset = StandardCharsets.UTF_8;
        this.charsetSource = "默认";
    }

    private String readSavedCharset() {
        if (charsetConfigFile == null || !charsetConfigFile.isFile()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(charsetConfigFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0 && "charset".equals(line.substring(0, eq).trim())) {
                    return line.substring(eq + 1).trim();
                }
            }
        } catch (Exception e) {
            log.warn("读取 {} 失败: {}", charsetConfigFile, e.getMessage());
        }
        return null;
    }

    /** 供界面使用：当前 JVM 支持哪些可选字符集。 */
    public List<String> availableCharsets() {
        List<String> list = new ArrayList<>();
        for (String name : CHARSET_CANDIDATES) {
            if (tryCharset(name) != null) {
                list.add(name);
            }
        }
        return list;
    }

    public String getSendCharsetName() {
        return sendCharset.name();
    }

    public String getCharsetSource() {
        return charsetSource;
    }

    /**
     * 当前编码下“中”字占几个字节。由服务端换算，因为浏览器只能按 UTF-8 编码，
     * 无法自己算出 GBK 的字节数。
     */
    public String charsetSample() {
        Charset cs = sendCharset;
        return "「中」=" + "中".getBytes(cs).length + " 字节";
    }

    /**
     * 运行时切换下发编码。切换后立刻对**已经连着的设备**生效，不需要重连。
     *
     * @return 切换后的字符集名
     */
    public String changeSendCharset(String name) {
        Charset parsed = tryCharset(name);
        if (parsed == null) {
            throw new IllegalArgumentException("不支持的字符集: " + name
                    + "，可选: " + String.join(" / ", availableCharsets()));
        }

        Charset previous = this.sendCharset;
        this.sendCharset = parsed;
        this.charsetSource = "界面切换";
        persistCharset(parsed.name());

        log.info("下发编码已切换: {} -> {} （对已连接设备立即生效）", previous.name(), parsed.name());
        broadcast("charset-changed", orderedMap(
                "charset", parsed.name(),
                "previous", previous.name(),
                "source", charsetSource,
                "at", System.currentTimeMillis()));
        broadcastState();
        return parsed.name();
    }

    private void persistCharset(String name) {
        if (charsetConfigFile == null) {
            return;
        }
        try {
            File parent = charsetConfigFile.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                log.warn("无法创建目录 {}，本次编码切换不会被保存", parent);
                return;
            }
            String content = "# ESP8266 测试台界面保存的设置，删除本文件即可恢复 app.send-charset 的值" + NL
                    + "charset=" + name + NL;
            // 用 UTF-8 无 BOM 写入，避免把 BOM 带进属性值
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(charsetConfigFile), StandardCharsets.UTF_8)) {
                writer.write(content);
            }
            log.info("下发编码已保存到 {}，下次启动依然生效", charsetConfigFile.getPath());
        } catch (Exception e) {
            log.warn("保存编码到 {} 失败: {}", charsetConfigFile, e.getMessage());
        }
    }

    private static Charset tryCharset(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        try {
            return Charset.forName(name.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        devices.values().forEach(DeviceConnection::close);
        devices.clear();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        if (clientPool != null) {
            clientPool.shutdownNow();
        }
        log.info("ESP8266 TCP 服务端已停止");
    }

    // ------------------------------------------------------------------ 接受连接

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                String id = "dev-" + idSeq.incrementAndGet();
                DeviceConnection connection = new DeviceConnection(
                        id, socket, properties.getMaxHistoryPerDevice(), receiveCharset);
                socket.setSoTimeout(properties.getTcp().getHandshakeTimeoutMillis());
                devices.put(id, connection);
                log.info("设备已接入 {} 来自 {}", id, connection.getRemoteAddress());
                broadcastState();
                clientPool.submit(() -> readLoop(connection));
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("接受连接异常: {}", e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------- 静默（心跳）过滤

    /**
     * 编译 {@code app.quiet-patterns}。
     *
     * <p>空字符串项和非法正则会跳过并告警——这一点很重要：如果把空串当成正则，
     * 空正则能匹配任意字符串，会把所有上报全部吞掉，那就再也看不到数据了。</p>
     */
    private void resolveQuietPatterns() {
        quietPatterns.clear();
        List<String> raw = properties.getQuietPatterns();
        if (raw != null) {
            for (String item : raw) {
                if (item == null || item.trim().isEmpty()) {
                    continue;
                }
                try {
                    quietPatterns.add(Pattern.compile(item.trim()));
                } catch (PatternSyntaxException e) {
                    log.warn("app.quiet-patterns 里的 \"{}\" 不是合法正则，已忽略: {}", item, e.getDescription());
                }
            }
        }
        if (quietPatterns.isEmpty()) {
            log.info("静默（心跳）过滤未启用：所有上报都会显示在界面数据日志里");
        } else {
            StringBuilder sb = new StringBuilder();
            for (Pattern pattern : quietPatterns) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(pattern.pattern());
            }
            log.info("静默（心跳）过滤已启用: {} —— 命中的报文只保活，不显示在数据日志里", sb);
        }
    }

    /** 该行是否属于“静默报文”（心跳之类只用于保活的报文）。 */
    private boolean isQuiet(String line) {
        for (Pattern pattern : quietPatterns) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private void readLoop(DeviceConnection connection) {
        boolean firstLine = true;
        try {
            String line;
            while ((line = connection.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    // 首条数据到达后取消握手超时，之后由心跳巡检判定离线
                    try {
                        connection.getSocket().setSoTimeout(0);
                    } catch (Exception ignored) {
                        // 忽略
                    }
                }
                String cleaned = stripBom(line).trim();
                if (cleaned.isEmpty()) {
                    continue;
                }
                if (isQuiet(cleaned)) {
                    // 静默报文：只保活 + 记账，不进数据日志、不进历史、不进控制台
                    connection.touchQuiet();
                    if (properties.isQuietEchoToConsole()) {
                        log.info("[{}] <- [静默] {}", connection.getId(), DeviceConnection.preview(cleaned));
                    }
                } else {
                    connection.touchReceived(cleaned);
                    if (properties.isEchoToConsole()) {
                        log.info("[{}] <- {}", connection.getId(), DeviceConnection.preview(cleaned));
                    }
                    broadcast("device-message", orderedMap(
                            "deviceId", connection.getId(),
                            "data", cleaned,
                            "at", System.currentTimeMillis()));
                }
                broadcastState();
            }
        } catch (SocketTimeoutException e) {
            log.info("设备 {} 迟迟未发送数据，断开连接", connection.getId());
        } catch (IOException e) {
            log.info("设备 {} 连接读取结束: {}", connection.getId(), e.getMessage());
        } finally {
            removeDevice(connection, "连接断开");
        }
    }

    private void removeDevice(DeviceConnection connection, String reason) {
        if (devices.remove(connection.getId()) == null) {
            return;
        }
        connection.close();
        log.info("设备已断开 {} ({})", connection.getId(), reason);
        broadcast("device-offline", orderedMap(
                "deviceId", connection.getId(),
                "reason", reason,
                "at", System.currentTimeMillis()));
        broadcastState();
    }

    private void checkHeartbeats() {
        long timeout = properties.getHeartbeatTimeoutMillis();
        if (timeout <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (DeviceConnection connection : new ArrayList<>(devices.values())) {
            if (connection.isClosed()) {
                removeDevice(connection, "连接已关闭");
                continue;
            }
            if (now - connection.getLastSeenAt() > timeout) {
                removeDevice(connection, "心跳超时 " + timeout + "ms");
            }
        }
    }

    // ------------------------------------------------------------------ 下发数据

    /** 向所有在线设备广播一行数据。 */
    public int broadcastToDevices(String text) {
        Charset charset = sendCharset;
        int ok = 0;
        for (DeviceConnection connection : devices.values()) {
            if (connection.sendLine(text, charset)) {
                ok++;
            }
        }
        logSent("广播", text, ok, charset);
        if (ok > 0) {
            broadcastState();
        }
        return ok;
    }

    /** 向指定设备发送一行数据。 */
    public boolean sendToDevice(String deviceId, String text) {
        DeviceConnection connection = devices.get(deviceId);
        if (connection == null) {
            throw new IllegalArgumentException("设备不存在或已离线: " + deviceId);
        }
        Charset charset = sendCharset;
        boolean ok = connection.sendLine(text, charset);
        logSent(deviceId, text, ok ? 1 : 0, charset);
        if (ok) {
            broadcastState();
        }
        return ok;
    }

    /**
     * 打印“发出去了什么”。中文乱码时这里是最直接的证据：
     * 对比日志里的十六进制和实际使用字符集，就能确认字节是否正确。
     */
    private void logSent(String target, String text, int delivered, Charset charset) {
        if (properties.isHexDumpOnSend()) {
            log.info("[{}] -> {} ({} 字节, {}): {}", target, DeviceConnection.preview(text),
                    text.getBytes(charset).length, charset.name(),
                    DeviceConnection.toHex(text, charset));
        } else {
            log.info("[{}] -> {} (已送达 {})", target, DeviceConnection.preview(text), delivered);
        }
    }

    /** 主动踢掉某个设备连接（界面上的“断开”按钮）。 */
    public boolean disconnectDevice(String deviceId) {
        DeviceConnection connection = devices.get(deviceId);
        if (connection == null) {
            return false;
        }
        removeDevice(connection, "服务端主动断开");
        return true;
    }

    // ------------------------------------------------------------------ 浏览器侧

    public void registerBrowser(WebSocketSession session) {
        browsers.put(session.getId(), session);
        sendTo(session, envelope("state", state()));
    }

    public void unregisterBrowser(WebSocketSession session) {
        browsers.remove(session.getId());
    }

    public Collection<DeviceConnection> connectedDevices() {
        return devices.values();
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getLastError() {
        return lastError.get();
    }

    public Map<String, Object> state() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> list = new ArrayList<>();
        for (DeviceConnection connection : devices.values()) {
            list.add(connection.toState(now, properties.getHeartbeatTimeoutMillis()));
        }
        list.sort((a, b) -> Long.compare((Long) a.get("connectedAt"), (Long) b.get("connectedAt")));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "state");
        state.put("at", now);
        state.put("tcpPort", properties.getTcp().getPort());
        state.put("serverAddresses", NetUtils.localIpv4Addresses());
        state.put("heartbeatTimeoutMillis", properties.getHeartbeatTimeoutMillis());
        state.put("sendCharset", sendCharset.name());
        state.put("charsetSource", charsetSource);
        state.put("availableCharsets", availableCharsets());
        // 只有当前编码的字节数能准确给出（其余编码浏览器算不了）
        state.put("charsetSamples", orderedMap(sendCharset.name(), charsetSample()));
        state.put("receiveCharset", receiveCharset.name());
        state.put("hexDumpOnSend", properties.isHexDumpOnSend());
        state.put("maxWorkLogLines", properties.getMaxWorkLogLines());
        state.put("deviceCount", list.size());
        state.put("devices", list);
        return state;
    }

    private Map<String, Object> envelope(String type, Map<String, Object> payload) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("at", System.currentTimeMillis());
        message.put("payload", payload);
        return message;
    }

    /** 便于按声明顺序构造小字典。 */
    private static Map<String, Object> orderedMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    public void broadcastState() {
        broadcastRaw(envelope("state", state()));
    }

    public void broadcast(String type, Map<String, Object> payload) {
        broadcastRaw(envelope(type, payload));
    }

    private void broadcastRaw(Map<String, Object> message) {
        if (browsers.isEmpty()) {
            return;
        }
        String json = JsonWriter.write(message);
        for (WebSocketSession session : new ArrayList<>(browsers.values())) {
            sendText(session, json);
        }
    }

    private void sendTo(WebSocketSession session, Map<String, Object> message) {
        sendText(session, JsonWriter.write(message));
    }

    private void sendText(WebSocketSession session, String json) {
        if (!session.isOpen()) {
            browsers.remove(session.getId());
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.debug("推送浏览器消息失败: {}", e.getMessage());
            browsers.remove(session.getId());
        }
    }

    private static String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }
}
