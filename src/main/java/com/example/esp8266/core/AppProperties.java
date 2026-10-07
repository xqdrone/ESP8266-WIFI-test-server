package com.example.esp8266.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 可通过 application.properties 覆盖的配置项（前缀 app.）。
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Tcp tcp = new Tcp();

    /** 心跳/离线判定超时（毫秒）。多久没收到单片机数据就标记为离线。 */
    private long heartbeatTimeoutMillis = 15_000L;

    /** 每个设备在浏览器界面保留的历史消息条数。 */
    private int maxHistoryPerDevice = 50;

    /** 浏览器界面工作区最多保留的日志行数。 */
    private int maxWorkLogLines = 500;

    /** 是否把收到的数据同时打印到控制台。 */
    private boolean echoToConsole = true;

    /**
     * 静默（心跳）匹配规则列表，每一项都是一个正则表达式，对收到的整行做“包含匹配”。
     *
     * <p>命中的上报只用于 <b>保活</b>：照样刷新在线状态、照样阻止心跳超时断开，
     * 但不进入浏览器数据日志、不进入设备历史，也不打印到控制台，也就是在界面上“隐身”。
     * 单片机定时发的 {@code Heart Beat Test} 就属于这一类。</p>
     *
     * <p>想让所有数据都显示出来，把本项留空即可：{@code app.quiet-patterns=}</p>
     *
     * <p>注意：写进 .properties 文件时反斜杠要写成两个（{@code \\s}），
     * 所以默认规则刻意不含反斜杠。</p>
     */
    private List<String> quietPatterns = new ArrayList<>(Arrays.asList("(?i)heart[ _-]?beat"));

    /** 静默报文是否也打印到服务端控制台。默认 false，保持安静。 */
    private boolean quietEchoToConsole = false;

    /**
     * 向单片机发送数据时使用的字符集，默认 UTF-8。
     *
     * <p>嵌入式调试场景建议改成 {@code GBK}：</p>
     * <ul>
     *   <li>中文 Windows 的串口助手默认按 GBK 解码，收到 UTF-8（汉字 3 字节）会显示乱码；</li>
     *   <li>STM32 上常见的 LCD/OLED 字库也是 GBK 编码，直接收 GBK 可以省掉转换表。</li>
     * </ul>
     * <p>可选值：{@code UTF-8}、{@code GBK}、{@code GB2312}、{@code GB18030}、{@code ISO-8859-1} 等
     * Java 支持的任意字符集名。</p>
     */
    private String sendCharset = "UTF-8";

    /**
     * 接收单片机数据时使用的字符集，默认 UTF-8。
     * <p>如果单片机上报的中文在浏览器/控制台里是乱码（例如它也按 GBK 回传），
     * 把它改成 {@code GBK} 即可。</p>
     */
    private String receiveCharset = "UTF-8";

    /** 向单片机发送数据时，是否在日志里打印十六进制字节（排查编码问题用）。 */
    private boolean hexDumpOnSend = false;

    /**
     * 界面切换下发编码后，把选择保存到这个文件，重启后依然生效。
     * 留空则不保存。相对路径按服务端的工作目录解析。
     */
    private String charsetConfigFile = "esp8266-console.properties";

    public static class Tcp {
        /** 单片机（ESP8266）连接的裸 TCP 端口。 */
        private int port = 9000;
        /** 接收缓冲区大小。 */
        private int receiveBuffer = 4096;
        /** 建连后等待首条数据的超时（毫秒），防止空闲连接占用线程。 */
        private int handshakeTimeoutMillis = 20_000;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getReceiveBuffer() {
            return receiveBuffer;
        }

        public void setReceiveBuffer(int receiveBuffer) {
            this.receiveBuffer = receiveBuffer;
        }

        public int getHandshakeTimeoutMillis() {
            return handshakeTimeoutMillis;
        }

        public void setHandshakeTimeoutMillis(int handshakeTimeoutMillis) {
            this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        }
    }

    public Tcp getTcp() {
        return tcp;
    }

    public long getHeartbeatTimeoutMillis() {
        return heartbeatTimeoutMillis;
    }

    public void setHeartbeatTimeoutMillis(long heartbeatTimeoutMillis) {
        this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
    }

    public int getMaxHistoryPerDevice() {
        return maxHistoryPerDevice;
    }

    public void setMaxHistoryPerDevice(int maxHistoryPerDevice) {
        this.maxHistoryPerDevice = maxHistoryPerDevice;
    }

    public int getMaxWorkLogLines() {
        return maxWorkLogLines;
    }

    public void setMaxWorkLogLines(int maxWorkLogLines) {
        this.maxWorkLogLines = maxWorkLogLines;
    }

    public boolean isEchoToConsole() {
        return echoToConsole;
    }

    public void setEchoToConsole(boolean echoToConsole) {
        this.echoToConsole = echoToConsole;
    }

    public List<String> getQuietPatterns() {
        return quietPatterns;
    }

    public void setQuietPatterns(List<String> quietPatterns) {
        this.quietPatterns = quietPatterns;
    }

    public boolean isQuietEchoToConsole() {
        return quietEchoToConsole;
    }

    public void setQuietEchoToConsole(boolean quietEchoToConsole) {
        this.quietEchoToConsole = quietEchoToConsole;
    }

    public String getSendCharset() {
        return sendCharset;
    }

    public void setSendCharset(String sendCharset) {
        this.sendCharset = sendCharset;
    }

    public boolean isHexDumpOnSend() {
        return hexDumpOnSend;
    }

    public void setHexDumpOnSend(boolean hexDumpOnSend) {
        this.hexDumpOnSend = hexDumpOnSend;
    }

    public String getCharsetConfigFile() {
        return charsetConfigFile;
    }

    public void setCharsetConfigFile(String charsetConfigFile) {
        this.charsetConfigFile = charsetConfigFile;
    }

    public String getReceiveCharset() {
        return receiveCharset;
    }

    public void setReceiveCharset(String receiveCharset) {
        this.receiveCharset = receiveCharset;
    }
}
