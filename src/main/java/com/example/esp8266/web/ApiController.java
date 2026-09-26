package com.example.esp8266.web;

import com.example.esp8266.core.DeviceHub;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 备用 REST 接口。界面主要走 WebSocket，这些接口方便用 curl / Postman 直接联调单片机。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final DeviceHub hub;

    public ApiController(DeviceHub hub) {
        this.hub = hub;
    }

    /** 服务端与设备总览。 */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return hub.state();
    }

    /** 服务端健康检查。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", hub.isRunning());
        map.put("tcpServerRunning", hub.isRunning());
        map.put("lastError", hub.getLastError());
        map.put("deviceCount", hub.state().get("deviceCount"));
        return map;
    }

    /**
     * 下发数据。
     * <p>POST /api/send?target=dev-1，body: {"data":"LED_ON"}；target 省略或为 all 时广播。</p>
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String target,
            @RequestBody(required = false) Map<String, Object> body,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String data) {

        String payload = data;
        if ((payload == null || payload.isEmpty()) && body != null && body.get("data") != null) {
            payload = String.valueOf(body.get("data"));
        }
        if (payload == null || payload.isEmpty()) {
            return ResponseEntity.badRequest().body(error("缺少参数 data"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (target == null || target.isEmpty() || "all".equals(target)) {
            int count = hub.broadcastToDevices(payload);
            result.put("ok", count > 0);
            result.put("delivered", count);
            result.put("message", count > 0 ? "已广播" : "当前没有在线设备");
            return ResponseEntity.ok(result);
        }
        try {
            boolean ok = hub.sendToDevice(target, payload);
            result.put("ok", ok);
            result.put("delivered", ok ? 1 : 0);
            result.put("message", ok ? "已发送" : "发送失败");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    /** 主动断开某个设备。 */
    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Map<String, Object>> disconnect(@PathVariable String id) {
        boolean ok = hub.disconnectDevice(id);
        if (!ok) {
            return ResponseEntity.status(404).body(error("设备不存在: " + id));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("message", "已断开 " + id);
        return ResponseEntity.ok(result);
    }

    /**
     * 实时切换下发编码，例如 {@code POST /api/charset?name=GBK}。
     * <p>切换会保存到配置文件，重启后依然生效。</p>
     * <p>只从查询参数取值，不要求请求体，这样 {@code curl -X POST ...?name=GBK}
     * 这种不带 Content-Type 的调用也能直接成功。</p>
     */
    @PostMapping("/charset")
    public ResponseEntity<Map<String, Object>> setCharset(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String name) {

        Map<String, Object> result = new LinkedHashMap<>();
        if (name == null || name.trim().isEmpty()) {
            result.put("ok", false);
            result.put("message", "缺少参数 name，例如 /api/charset?name=GBK");
            result.put("available", hub.availableCharsets());
            return ResponseEntity.badRequest().body(result);
        }
        try {
            String applied = hub.changeSendCharset(name.trim());
            result.put("ok", true);
            result.put("sendCharset", applied);
            result.put("source", hub.getCharsetSource());
            result.put("message", "下发编码已切换为 " + applied + "（已保存，重启后保持）");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("ok", false);
            result.put("message", e.getMessage());
            result.put("available", hub.availableCharsets());
            return ResponseEntity.badRequest().body(result);
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("message", message);
        return map;
    }
}
