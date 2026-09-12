package com.omniforge.gateway.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QQ 网关（WebSocket）协议消息构造与解析（纯函数，可独立测试）：
 * opcode 2 Identify / 6 Resume / 1 Heartbeat 的 JSON 构造，
 * 与下行 payload {@code {id, op, d, s, t}} 的字段提取。
 */
public final class QqGatewayProtocol {

    /** 单聊 + 群@ + 好友事件（1<<25） */
    public static final long INTENT_GROUP_AND_C2C = 1L << 25;

    private QqGatewayProtocol() {
    }

    /** opcode 2 Identify：token 格式 "QQBot {accessToken}"，shard [0,1] 不分片 */
    public static String identify(String accessToken) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", 2);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("token", "QQBot " + accessToken);
        d.put("intents", INTENT_GROUP_AND_C2C);
        d.put("shard", List.of(0, 1));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("$os", System.getProperty("os.name", "unknown"));
        properties.put("$browser", "omniforge");
        properties.put("$device", "omniforge");
        d.put("properties", properties);
        payload.put("d", d);
        return writeJson(payload);
    }

    /** opcode 6 Resume：断线重连补发遗漏事件 */
    public static String resume(String accessToken, String sessionId, long lastSeq) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", 6);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("token", "QQBot " + accessToken);
        d.put("session_id", sessionId);
        d.put("seq", lastSeq);
        payload.put("d", d);
        return writeJson(payload);
    }

    /** opcode 1 Heartbeat：d 为客户端收到的最新 s（首次为 null） */
    public static String heartbeat(Long lastSeq) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", 1);
        payload.put("d", lastSeq);
        return writeJson(payload);
    }

    /** 下行载荷 opcode（非 JSON / 缺 op 时返回 -1） */
    public static int opcodeOf(String json) {
        try {
            return new ObjectMapper().readTree(json).path("op").asInt(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 下行载荷事件类型 t（非 JSON / 缺 t 时返回空串） */
    public static String eventTypeOf(String json) {
        try {
            return new ObjectMapper().readTree(json).path("t").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** 下行载荷序列号 s（缺省 -1） */
    public static long seqOf(String json) {
        try {
            return new ObjectMapper().readTree(json).path("s").asLong(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Hello 载荷的心跳周期（毫秒，缺省 45000） */
    public static long heartbeatIntervalOf(String json) {
        try {
            return new ObjectMapper().readTree(json)
                    .path("d").path("heartbeat_interval").asLong(45000);
        } catch (Exception e) {
            return 45000;
        }
    }

    /** READY 载荷的 session_id（缺省空串） */
    public static String sessionIdOf(String json) {
        try {
            JsonNode node = new ObjectMapper().readTree(json).path("d").path("session_id");
            return node.isMissingNode() ? "" : node.asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** dispatch 载荷转换为适配器可解析的事件体（d 部分，保留 op/t 供适配器过滤） */
    public static String eventPayloadOf(String json) {
        return json;
    }

    private static String writeJson(Map<String, Object> payload) {
        try {
            return new ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("网关协议 JSON 构造失败：" + e.getMessage(), e);
        }
    }
}
