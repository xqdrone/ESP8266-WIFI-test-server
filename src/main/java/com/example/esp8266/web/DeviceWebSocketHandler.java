package com.example.esp8266.web;

import com.example.esp8266.core.DeviceHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器 <-> 服务端 的 WebSocket 通道。
 *
 * <p>浏览器发送的指令格式（单行 JSON）：</p>
 * <pre>
 * {"cmd":"send",       "data":"LED_ON", "target":"dev-1"}   // 指定设备下发；target 为空/省略则广播
 * {"cmd":"disconnect", "target":"dev-1"}                    // 断开某个设备
 * {"cmd":"set-charset","charset":"GBK"}                     // 实时切换下发编码
 * {"cmd":"ping"}                                            // 心跳，用于界面显示“服务端在线”
 * </pre>
 */
@Component
public class DeviceWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceWebSocketHandler.class);

    private final DeviceHub hub;

    public DeviceWebSocketHandler(DeviceHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("浏览器控制台已连接: {}", session.getId());
        hub.registerBrowser(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("浏览器控制台已断开: {}", session.getId());
        hub.unregisterBrowser(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        ParsedCommand command = ParsedCommand.parse(payload);
        String cmd = command.getString("cmd", "");

        try {
            switch (cmd) {
                case "send": {
                    String data = command.getString("data", "");
                    String target = command.getString("target", "");
                    if (data.isEmpty()) {
                        reply(session, "error", "发送内容不能为空");
                        return;
                    }
                    if (target == null || target.isEmpty() || "all".equals(target)) {
                        int count = hub.broadcastToDevices(data);
                        if (count == 0) {
                            reply(session, "warn", "当前没有在线设备，数据未下发");
                        } else {
                            reply(session, "sent", "已向 " + count + " 个设备下发: " + data);
                        }
                    } else {
                        hub.sendToDevice(target, data);
                        reply(session, "sent", "已向 " + target + " 下发: " + data);
                    }
                    break;
                }
                case "disconnect": {
                    String target = command.getString("target", "");
                    boolean ok = hub.disconnectDevice(target);
                    reply(session, ok ? "info" : "warn", ok ? "已断开 " + target : "设备不存在: " + target);
                    break;
                }
                case "set-charset": {
                    String charset = command.getString("charset", "");
                    String applied = hub.changeSendCharset(charset);
                    reply(session, "info", "下发编码已切换为 " + applied + "（对已连接设备立即生效，已保存）");
                    break;
                }
                case "ping":
                    reply(session, "pong", "ok");
                    break;
                default:
                    reply(session, "error", "未知指令: " + cmd);
            }
        } catch (IllegalArgumentException e) {
            reply(session, "error", e.getMessage());
        } catch (Exception e) {
            log.warn("处理浏览器指令失败: {}", payload, e);
            reply(session, "error", "服务端处理失败: " + e.getMessage());
        }
    }

    private void reply(WebSocketSession session, String level, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();        payload.put("level", level);
        payload.put("text", text);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "notice");
        message.put("at", System.currentTimeMillis());
        message.put("payload", payload);
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(
                            com.example.esp8266.core.JsonWriter.write(message)));
                }
            }
        } catch (Exception e) {
            log.debug("回复浏览器失败: {}", e.getMessage());
        }
    }
}
