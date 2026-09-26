package com.example.esp8266.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析浏览器发来的单行 JSON 指令，避免为了一个小工具再引入 JSON 库。
 * 使用 Jackson（Spring Boot 已自带）把 JSON 转成 Map。
 */
public final class ParsedCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> values;

    private ParsedCommand(Map<String, Object> values) {
        this.values = values;
    }

    /** 解析失败时返回只含 cmd 的空指令，由调用方按“未知指令”处理。 */
    public static ParsedCommand parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ParsedCommand(new LinkedHashMap<>());
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
            });
            return new ParsedCommand(map != null ? map : new LinkedHashMap<>());
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("cmd", "");
            fallback.put("raw", json);
            return new ParsedCommand(fallback);
        }
    }

    public String getString(String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public Map<String, Object> asMap() {
        return values;
    }
}
