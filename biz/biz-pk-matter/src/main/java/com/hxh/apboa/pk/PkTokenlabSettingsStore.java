package com.hxh.apboa.pk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hxh.apboa.common.dto.PkTokenlabSettingsDTO;
import com.hxh.apboa.common.util.JsonUtils;
import com.hxh.apboa.common.vo.PkTokenlabSettingsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从租户 config JSON 读写 PatentKing Tokenlab 配置。
 */
@Component
@RequiredArgsConstructor
public class PkTokenlabSettingsStore {

    private static final String CONFIG_KEY = "patentKing";
    private static final String TOKENLAB_KEY = "tokenlab";

    private final JdbcTemplate jdbcTemplate;

    public PkTokenlabSettingsVO getForTenant(Long tenantId) {
        Map<String, Object> raw = readRaw(tenantId);
        PkTokenlabSettingsVO vo = new PkTokenlabSettingsVO();
        vo.setApiKeyConfigured(StringUtils.hasText(str(raw, "apiKey")));
        vo.setBaseUrl(defaultBaseUrl(str(raw, "baseUrl")));
        vo.setFallbackBaseUrl(defaultFallback(str(raw, "fallbackBaseUrl")));
        vo.setModel(defaultModel(str(raw, "model")));
        vo.setSize(defaultSize(str(raw, "size")));
        vo.setQuality(defaultQuality(str(raw, "quality")));
        vo.setNetwork(defaultNetwork(str(raw, "network")));
        vo.setProxy(str(raw, "proxy"));
        return vo;
    }

    /** 含 apiKey，仅供服务端导出使用，禁止返回前端。 */
    public Map<String, Object> getExportConfig(Long tenantId) {
        Map<String, Object> raw = readRaw(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        String apiKey = str(raw, "apiKey");
        if (!StringUtils.hasText(apiKey)) {
            return out;
        }
        out.put("api_key", apiKey);
        out.put("base_url", defaultBaseUrl(str(raw, "baseUrl")));
        String fallback = str(raw, "fallbackBaseUrl");
        if (StringUtils.hasText(fallback)) {
            out.put("fallback_base_url", fallback.trim());
        } else {
            out.put("fallback_base_url", defaultFallback(null));
        }
        out.put("model", defaultModel(str(raw, "model")));
        out.put("size", defaultSize(str(raw, "size")));
        out.put("quality", defaultQuality(str(raw, "quality")));
        out.put("network", defaultNetwork(str(raw, "network")));
        String proxy = str(raw, "proxy");
        if (StringUtils.hasText(proxy)) {
            out.put("proxy", proxy.trim());
        }
        return out;
    }

    public boolean isConfigured(Long tenantId) {
        return StringUtils.hasText(str(readRaw(tenantId), "apiKey"));
    }

    public PkTokenlabSettingsVO saveForTenant(Long tenantId, PkTokenlabSettingsDTO dto) {
        if (dto == null) {
            return getForTenant(tenantId);
        }
        Map<String, Object> root = readTenantConfigRoot(tenantId);
        @SuppressWarnings("unchecked")
        Map<String, Object> pk = (Map<String, Object>) root.computeIfAbsent(CONFIG_KEY, k -> new LinkedHashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> existing = pk.get(TOKENLAB_KEY) instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>();

        if (StringUtils.hasText(dto.getApiKey()) && !"***".equals(dto.getApiKey().trim())) {
            existing.put("apiKey", dto.getApiKey().trim());
        }
        if (dto.getBaseUrl() != null) {
            existing.put("baseUrl", dto.getBaseUrl().trim());
        }
        if (dto.getFallbackBaseUrl() != null) {
            existing.put("fallbackBaseUrl", dto.getFallbackBaseUrl().trim());
        }
        if (dto.getModel() != null) {
            existing.put("model", dto.getModel().trim());
        }
        if (dto.getSize() != null) {
            existing.put("size", dto.getSize().trim());
        }
        if (dto.getQuality() != null) {
            existing.put("quality", dto.getQuality().trim());
        }
        if (dto.getNetwork() != null) {
            existing.put("network", dto.getNetwork().trim());
        }
        if (dto.getProxy() != null) {
            existing.put("proxy", dto.getProxy().trim());
        }

        pk.put(TOKENLAB_KEY, existing);
        root.put(CONFIG_KEY, pk);
        writeTenantConfigRoot(tenantId, root);
        return getForTenant(tenantId);
    }

    private Map<String, Object> readRaw(Long tenantId) {
        Map<String, Object> root = readTenantConfigRoot(tenantId);
        Object pk = root.get(CONFIG_KEY);
        if (!(pk instanceof Map<?, ?> pkMap)) {
            return Map.of();
        }
        Object tl = pkMap.get(TOKENLAB_KEY);
        if (!(tl instanceof Map<?, ?> tlMap)) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) tlMap;
        return cast;
    }

    private Map<String, Object> readTenantConfigRoot(Long tenantId) {
        if (tenantId == null) {
            return new LinkedHashMap<>();
        }
        String json = jdbcTemplate.query(
                "SELECT config FROM tenant WHERE id = ? AND enabled = 1",
                rs -> rs.next() ? rs.getString(1) : null,
                tenantId);
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parse(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeTenantConfigRoot(Long tenantId, Map<String, Object> root) {
        String json = JsonUtils.toJsonStr(root);
        jdbcTemplate.update("UPDATE tenant SET config = ? WHERE id = ?", json, tenantId);
    }

    private static String str(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    static String defaultBaseUrl(String v) {
        return StringUtils.hasText(v) ? v.trim() : "https://api.tokenlab.cc.cd/v1";
    }

    static String defaultFallback(String v) {
        return StringUtils.hasText(v) ? v.trim() : "https://hk.tokenlab.ccwu.cc/v1";
    }

    static String defaultModel(String v) {
        return StringUtils.hasText(v) ? v.trim() : "gpt-image-2";
    }

    static String defaultSize(String v) {
        return StringUtils.hasText(v) ? v.trim() : "auto";
    }

    static String defaultQuality(String v) {
        return StringUtils.hasText(v) ? v.trim() : "high";
    }

    static String defaultNetwork(String v) {
        return StringUtils.hasText(v) ? v.trim() : "direct";
    }

    /** 归一化图示模式：仅 png | auto；兼容旧值。 */
    public static String normalizeDiagramMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return "png";
        }
        String m = mode.trim().toLowerCase();
        if ("auto".equals(m) || "tokenlab".equals(m) || "image-api".equals(m)) {
            return "auto";
        }
        return "png";
    }
}
