package com.hxh.apboa.pk;

import com.hxh.apboa.common.util.JsonUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 案件与对话会话的绑定信息，写入 pk_matter.meta_json。
 */
public final class PkMatterChatMeta {

    public static final String SESSIONS_KEY = "chatSessions";
    public static final String LAST_KEY = "lastSessionId";

    private PkMatterChatMeta() {
    }

    public static String bind(String metaJson, long sessionId, String title) {
        Map<String, Object> root = PkPatentPointsMeta.readRoot(metaJson);
        List<Map<String, Object>> sessions = readSessions(metaJson);
        String id = String.valueOf(sessionId);
        boolean found = false;
        for (Map<String, Object> row : sessions) {
            if (id.equals(String.valueOf(row.get("id")))) {
                row.put("title", StringUtils.hasText(title) ? title : row.get("title"));
                row.put("boundAt", Instant.now().toString());
                found = true;
                break;
            }
        }
        if (!found) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("title", StringUtils.hasText(title) ? title : "对话");
            row.put("boundAt", Instant.now().toString());
            sessions.add(row);
        }
        root.put(SESSIONS_KEY, sessions);
        root.put(LAST_KEY, id);
        return JsonUtils.toJsonStr(root);
    }

    public static String lastSessionId(String metaJson) {
        Object raw = PkPatentPointsMeta.readRoot(metaJson).get(LAST_KEY);
        return raw == null ? null : String.valueOf(raw);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readSessions(String metaJson) {
        Object raw = PkPatentPointsMeta.readRoot(metaJson).get(SESSIONS_KEY);
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
        }
        return out;
    }
}
