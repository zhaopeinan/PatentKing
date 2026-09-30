package com.hxh.apboa.pk;

import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.common.util.JsonUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 专利查新 sidecar 调用（供 Groovy 动态工具复用，避免 Groovy 字符串插值问题）。
 */
@Component
public class PkPriorArtSupport {

    private static PkPriorArtSupport self;

    @PostConstruct
    void register() {
        self = this;
    }

    public static Map<String, Object> searchFromTool(Map<String, Object> params) {
        if (self == null) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("degrade", "websearch");
            failed.put("hits", List.of());
            failed.put("message", "PkPriorArtSupport 未初始化");
            return failed;
        }
        return self.doSearch(params);
    }

    public Map<String, Object> doSearch(Map<String, Object> params) {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("ok", false);
        failed.put("degrade", "websearch");
        failed.put("hits", List.of());

        String query = str(params, "query");
        if (query.isEmpty()) {
            failed.put("message", "query 为空");
            return failed;
        }

        int limit = 8;
        String limitRaw = str(params, "limit");
        if (StringUtils.hasText(limitRaw)) {
            try {
                limit = Integer.parseInt(limitRaw);
            } catch (NumberFormatException ignored) {
                limit = 8;
            }
        }
        limit = Math.max(1, Math.min(limit, 20));

        try {
            JsonNode result = callSidecar(query, limit);
            if (result == null) {
                failed.put("message", "patent-tools 无响应，请降级网页搜索");
                return failed;
            }
            return jsonNodeToMap(result);
        } catch (IllegalStateException e) {
            failed.put("message", e.getMessage());
            return failed;
        }
    }

    JsonNode callSidecar(String query, int limit) {
        String base = System.getenv("PK_TOOLS_BASE_URL");
        if (!StringUtils.hasText(base)) {
            base = "http://patent-tools:3070";
        }
        base = base.replaceAll("/$", "");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", query);
            payload.put("limit", limit);
            byte[] body = JsonUtils.toJsonStr(payload).getBytes(StandardCharsets.UTF_8);
            URL url = URI.create(base + "/v1/cnipa/search").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            // 国知局 Playwright 过 WAF：冷启动/排队可达数分钟；须低于 runtime 工具超时
            conn.setReadTimeout(300_000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                throw new IllegalStateException("sidecar HTTP " + code + " 无响应体，请降级网页搜索");
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (code >= 400) {
                throw new IllegalStateException("sidecar HTTP " + code + ": " + raw);
            }
            return JsonUtils.parse(raw);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法连接 patent-tools（" + e.getMessage() + "）。请确认 sidecar 在运行，或降级网页搜索。", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of("ok", false, "degrade", "websearch", "hits", List.of(), "message", "空响应");
        }
        try {
            return JsonUtils.parse(node.toString(), Map.class);
        } catch (RuntimeException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", node.path("ok").asBoolean(false));
            out.put("message", node.path("message").asText(""));
            out.put("degrade", node.path("degrade").asText("websearch"));
            out.put("source", node.path("source").asText(""));
            out.put("query", node.path("query").asText(""));
            return out;
        }
    }

    private static String str(Map<String, Object> params, String key) {
        if (params == null) {
            return "";
        }
        Object v = params.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
