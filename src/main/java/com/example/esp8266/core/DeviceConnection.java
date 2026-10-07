package com.example.esp8266.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一条来自单片机（ESP8266）的 TCP 长连接。
 *
 * <p>协议非常简单，面向嵌入式实现：<b>一行一条消息</b>，以 \r\n 或 \n 结尾；因此无论
 * 单片机发的是纯文本指令还是 JSON（如 {"cmd":"led","value":1}），服务端都能原样收下并转发给浏览器。</p>
 */
public class DeviceConnection {

    /** 统一行尾：ESP8266 端用 readStringUntil('\n') / readLine() 都能正确分行。 */
    public static final String LINE_SEPARATOR = "\r\n";

    private final String id;
    private final Socket socket;
    private final BufferedReader reader;
    private final java.io.OutputStream rawOut;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final String remoteAddress;
    private final int remotePort;
    private final long connectedAt = System.currentTimeMillis();

    private final AtomicLong receivedCount = new AtomicLong();
    private final AtomicLong sentCount = new AtomicLong();
    /** 被静默（心跳）规则拦下的条数：只保活，不进日志/历史，也不计入 receivedCount。 */
    private final AtomicLong quietCount = new AtomicLong();
    private volatile long lastSeenAt = System.currentTimeMillis();
    /** 最近一条“业务数据”的时间；心跳不会刷新它，界面的“最近数据”看的就是这个。 */
    private volatile long lastDataAt = System.currentTimeMillis();
    /** 最近一条被静默报文的时间，0 表示还没收到过。 */
    private volatile long lastQuietAt = 0L;
    private volatile String name;

    private final Deque<String> history = new ArrayDeque<>();
    private final int maxHistory;

    public DeviceConnection(String id, Socket socket, int maxHistory, Charset receiveCharset) throws IOException {
        this.id = id;
        this.socket = socket;
        this.maxHistory = maxHistory;
        this.remoteAddress = socket.getInetAddress().getHostAddress();
        this.remotePort = socket.getPort();
        // 接收按 app.receive-charset 解码（默认 UTF-8）；单片机若回传 GBK 中文，把它设为 GBK
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), receiveCharset));
        // 发送按 DeviceHub 实时传入的字符集编码（界面上可随时切换）
        this.rawOut = socket.getOutputStream();
    }

    public String getId() {
        return id;
    }

    public Socket getSocket() {
        return socket;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public long getReceivedCount() {
        return receivedCount.get();
    }

    public long getSentCount() {
        return sentCount.get();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 阻塞读取一行，返回 null 表示对端已关闭连接。 */
    public String readLine() throws IOException {
        return reader.readLine();
    }

    /** 收到一条“业务数据”后调用，刷新活跃时间、计数并记录历史。 */
    public void touchReceived(String line) {
        receivedCount.incrementAndGet();
        long now = System.currentTimeMillis();
        lastSeenAt = now;
        lastDataAt = now;
        synchronized (history) {
            history.addLast(line);
            while (history.size() > maxHistory) {
                history.removeFirst();
            }
        }
    }

    /**
     * 收到一条命中静默（心跳）规则的上报后调用。
     *
     * <p>关键点：仍然刷新 {@link #lastSeenAt}，否则 {@code DeviceHub.checkHeartbeats()}
     * 会在超时后把设备判定为离线并断开。差别只在于它不计入数据条数、不进历史，
     * 因此不会出现在浏览器数据日志和控制台里。</p>
     */
    public void touchQuiet() {
        long now = System.currentTimeMillis();
        lastSeenAt = now;
        lastQuietAt = now;
        quietCount.incrementAndGet();
    }

    /**
     * 发送一行数据。写操作加锁，避免多个浏览器同时点按钮时字节交错。
     *
     * <p>文本按传入的 {@code charset} 编码成字节后发出。字符集由 DeviceHub 在每次发送时
     * 实时传入（而不是构造时固定），这样界面上切换编码后，已经连着的设备立刻生效，
     * 不需要重新插拔或重连。</p>
     *
     * @return 是否发送成功
     */
    public boolean sendLine(String text, Charset charset) {
        if (closed.get()) {
            return false;
        }
        synchronized (writeLock) {
            try {
                // 自己编码 + write(byte[])，这样字节数是确定的（AT+CIPSEND 场景也好核对）
                rawOut.write(text.getBytes(charset));
                rawOut.write(LINE_SEPARATOR.getBytes(StandardCharsets.US_ASCII));
                rawOut.flush();
                sentCount.incrementAndGet();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** 把字符串按指定字符集编码成十六进制，便于确认“到底发出去了什么字节”。 */
    public static String toHex(String text, Charset charset) {
        byte[] bytes = text.getBytes(charset);
        StringBuilder sb = new StringBuilder(bytes.length * 3 + 16);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    /** 日志里截断过长内容。 */
    public static String preview(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }

    public List<String> snapshotHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public Map<String, Object> toState(long now, long heartbeatTimeoutMillis) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("remote", remoteAddress + ":" + remotePort);
        map.put("remoteAddress", remoteAddress);
        map.put("remotePort", remotePort);
        map.put("name", name != null ? name : "");
        map.put("connectedAt", connectedAt);
        map.put("connectedSeconds", Math.max(0L, (now - connectedAt) / 1000L));
        map.put("lastSeenAt", lastSeenAt);
        map.put("idleMillis", Math.max(0L, now - lastSeenAt));
        map.put("lastDataAt", lastDataAt);
        // 界面上的“最近数据”用这个：被静默的心跳不算数据
        map.put("dataIdleMillis", Math.max(0L, now - lastDataAt));
        map.put("quietCount", quietCount.get());
        map.put("lastQuietAt", lastQuietAt);
        // -1 表示这次连接还没收到过心跳
        map.put("quietIdleMillis", lastQuietAt > 0L ? Math.max(0L, now - lastQuietAt) : -1L);
        map.put("received", receivedCount.get());
        map.put("sent", sentCount.get());
        map.put("history", snapshotHistory());
        boolean stale = heartbeatTimeoutMillis > 0 && (now - lastSeenAt) > heartbeatTimeoutMillis;
        map.put("status", stale ? "STALE" : "ONLINE");
        return map;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }
}
