package com.hxh.apboa.pk;

import com.hxh.apboa.common.util.JsonUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 案件 metaJson 中的场景（细意图）读写。
 */
public final class PkMatterSceneMeta {

    public static final String KEY = "scene";

    private PkMatterSceneMeta() {
    }

    public static String readScene(String metaJson) {
        if (!StringUtils.hasText(metaJson)) {
            return "";
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JsonUtils.parse(metaJson, Map.class);
            if (map == null) {
                return "";
            }
            Object v = map.get(KEY);
            return v == null ? "" : String.valueOf(v).trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static String mergeScene(String metaJson, String scene) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (StringUtils.hasText(metaJson)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = JsonUtils.parse(metaJson, Map.class);
                if (parsed != null) {
                    map.putAll(parsed);
                }
            } catch (RuntimeException ignored) {
                // keep empty and overwrite
            }
        }
        if (StringUtils.hasText(scene)) {
            map.put(KEY, scene.trim());
        } else {
            map.remove(KEY);
        }
        return JsonUtils.toJsonStr(map);
    }
}
