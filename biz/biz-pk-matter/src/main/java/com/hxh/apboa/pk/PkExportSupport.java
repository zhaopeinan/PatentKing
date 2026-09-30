package com.hxh.apboa.pk;

import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.common.util.JsonUtils;
import com.hxh.apboa.common.util.TenantUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交底 Word 导出（供动态工具与业务层复用）。
 */
@Component
@RequiredArgsConstructor
public class PkExportSupport {

    private static PkExportSupport self;

    private final PkTokenlabSettingsStore tokenlabStore;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void register() {
        self = this;
    }

    public static Map<String, Object> exportFromTool(Map<String, Object> params, Map<String, Object> contextParams) {
        if (self == null) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("message", "PkExportSupport 未初始化");
            return failed;
        }
        return self.doExport(params, contextParams);
    }

    public Map<String, Object> doExport(Map<String, Object> params, Map<String, Object> contextParams) {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("ok", false);

        String markdown = str(params, "markdown");
        if (markdown.isEmpty()) {
            failed.put("message", "markdown 为空，无法导出 Word");
            return failed;
        }
        String title = str(params, "title");
        if (title.isEmpty()) {
            title = "disclosure";
        }
        String matterId = str(params, "matter_id");
        if (matterId.isEmpty() && contextParams != null) {
            Object mid = contextParams.get("matterId");
            if (mid == null) {
                mid = contextParams.get("matter_id");
            }
            if (mid != null) {
                matterId = String.valueOf(mid).trim();
            }
        }

        String modeParam = str(params, "diagram_mode");
        String mode = "png";
        if (StringUtils.hasText(matterId)) {
            String fromMatter = loadDiagramModeFromMatter(matterId);
            if (StringUtils.hasText(fromMatter)) {
                mode = PkTokenlabSettingsStore.normalizeDiagramMode(fromMatter);
            } else if (StringUtils.hasText(modeParam)) {
                mode = PkTokenlabSettingsStore.normalizeDiagramMode(modeParam);
            }
        } else if (StringUtils.hasText(modeParam)) {
            mode = PkTokenlabSettingsStore.normalizeDiagramMode(modeParam);
        }
        if ("auto".equals(mode) && !tokenlabStore.isConfigured(TenantUtils.getCurrentTenantId())) {
            failed.put("message", "已选择「自动生成」图示，但尚未配置 Tokenlab。"
                    + "请租户管理员在「系统设置 → PatentKing 生图」中填写 Tokenlab API Key 后重试。");
            failed.put("needs_tokenlab_config", true);
            return failed;
        }

        try {
            JsonNode result = callExport(markdown, title, mode, TenantUtils.getCurrentTenantId());
            if (result == null) {
                failed.put("message", "patent-tools 无响应");
                return failed;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", result.path("ok").asBoolean(false));
            out.put("message", result.path("message").asText(""));
            out.put("export_id", result.path("export_id").asText(""));
            out.put("filename", result.path("filename").asText(""));
            out.put("figure_count", result.path("figure_count").asInt(0));
            out.put("failed_count", result.path("failed_count").asInt(0));
            out.put("diagram_mode", mode);
            out.put("matter_id", matterId);
            if (result.path("figures").isArray()) {
                out.put("figures", JsonUtils.parse(result.path("figures").toString(), java.util.List.class));
            }
            out.put("hint", "图示已由机器渲染。请告知用户到案件页下载 Word，或等待前端自动登记产物。禁止再让用户手工渲染 mermaid。");
            return out;
        } catch (IllegalStateException e) {
            failed.put("message", e.getMessage());
            return failed;
        }
    }

    public JsonNode callExport(String markdown, String title, String mode, Long tenantId) {
        String base = System.getenv("PK_TOOLS_BASE_URL");
        if (!StringUtils.hasText(base)) {
            base = "http://patent-tools:3070";
        }
        base = base.replaceAll("/$", "");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("markdown", markdown);
            payload.put("title", title);
            payload.put("diagram_mode", PkTokenlabSettingsStore.normalizeDiagramMode(mode));
            payload.put("return_base64", false);
            if ("auto".equals(payload.get("diagram_mode"))) {
                Map<String, Object> tokenlab = tokenlabStore.getExportConfig(tenantId);
                if (tokenlab.isEmpty()) {
                    throw new IllegalStateException("Tokenlab 未配置，无法使用自动生成图示");
                }
                payload.put("tokenlab", tokenlab);
            }
            return postSidecar(base + "/v1/export/md-to-docx", payload, 600_000);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法连接 patent-tools：" + e.getMessage(), e);
        }
    }

    public JsonNode createExportSession(String markdown, String mode, Long tenantId) {
        String base = sidecarBase();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("markdown", markdown);
        payload.put("diagram_mode", PkTokenlabSettingsStore.normalizeDiagramMode(mode));
        if ("auto".equals(payload.get("diagram_mode")) && !tokenlabStore.isConfigured(tenantId)) {
            throw new IllegalStateException(
                    "已选择「自动生成」图示，但尚未配置 Tokenlab。请管理员在「系统设置 → PatentKing 生图」中填写 API Key。");
        }
        return postSidecar(base + "/v1/export/session", payload, 60_000);
    }

    public JsonNode renderExportFigure(String sessionId, int index, String mode, boolean force, Long tenantId) {
        String base = sidecarBase();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(mode)) {
            payload.put("diagram_mode", PkTokenlabSettingsStore.normalizeDiagramMode(mode));
        }
        payload.put("force", force);
        if ("auto".equals(payload.get("diagram_mode"))) {
            Map<String, Object> tokenlab = tokenlabStore.getExportConfig(tenantId);
            if (tokenlab.isEmpty()) {
                throw new IllegalStateException("Tokenlab 未配置，无法使用自动生成图示");
            }
            payload.put("tokenlab", tokenlab);
        }
        return postSidecar(base + "/v1/export/session/" + sessionId + "/figure/" + index, payload, 600_000);
    }

    public JsonNode finalizeExportSession(String sessionId, String title, String mode) {
        String base = sidecarBase();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", StringUtils.hasText(title) ? title : "disclosure");
        if (StringUtils.hasText(mode)) {
            payload.put("diagram_mode", PkTokenlabSettingsStore.normalizeDiagramMode(mode));
        }
        return postSidecar(base + "/v1/export/session/" + sessionId + "/finalize", payload, 120_000);
    }

    private String sidecarBase() {
        String base = System.getenv("PK_TOOLS_BASE_URL");
        if (!StringUtils.hasText(base)) {
            base = "http://patent-tools:3070";
        }
        return base.replaceAll("/$", "");
    }

    private JsonNode postSidecar(String url, Map<String, Object> payload, int readTimeoutMs) {
        try {
            byte[] body = JsonUtils.toJsonStr(payload).getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                throw new IllegalStateException("patent-tools HTTP " + code + " 无响应体");
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (code >= 400) {
                throw new IllegalStateException("patent-tools HTTP " + code + ": " + raw);
            }
            return JsonUtils.parse(raw);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法连接 patent-tools：" + e.getMessage(), e);
        }
    }

    private String loadDiagramModeFromMatter(String matterId) {
        try {
            long id = Long.parseLong(matterId.trim());
            String meta = jdbcTemplate.query(
                    "SELECT meta_json FROM pk_matter WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    id);
            if (!StringUtils.hasText(meta)) {
                return "";
            }
            Object dm = PkDeliveryMeta.readDelivery(meta).get("diagramMode");
            return dm == null ? "" : String.valueOf(dm).trim();
        } catch (RuntimeException e) {
            return "";
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
