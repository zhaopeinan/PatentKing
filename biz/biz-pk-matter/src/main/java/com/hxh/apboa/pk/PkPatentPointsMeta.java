package com.hxh.apboa.pk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hxh.apboa.common.dto.PkPatentPointsDTO;
import com.hxh.apboa.common.util.JsonUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将专利点闸门状态写入 / 读出 pk_matter.meta_json。
 */
public final class PkPatentPointsMeta {

    public static final String KEY = "patentPoints";
    public static final String GATE = "PATENT_POINTS_CONFIRMED";

    private PkPatentPointsMeta() {
    }

    public static Map<String, Object> readRoot(String metaJson) {
        if (!StringUtils.hasText(metaJson)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parse(metaJson, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException ignored) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readPoints(String metaJson) {
        Object raw = readRoot(metaJson).get(KEY);
        if (raw instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    public static String mergeSubmit(String metaJson, PkPatentPointsDTO dto) {
        Map<String, Object> root = readRoot(metaJson);
        Map<String, Object> existing = readPoints(metaJson);
        Map<String, Object> incoming = toMap(dto);
        keepInventoryIfIncomingEmpty(existing, incoming);
        keepNotesIfIncomingBlank(existing, incoming);
        applyBuckets(incoming);
        incoming.put("submittedAt", Instant.now().toString());
        incoming.remove("confirmedAt");
        incoming.remove("gate");
        root.put(KEY, incoming);
        return JsonUtils.toJsonStr(root);
    }

    public static String mergeConfirm(String metaJson, PkPatentPointsDTO dto) {
        Map<String, Object> root = readRoot(metaJson);
        Map<String, Object> existing = readPoints(metaJson);
        Map<String, Object> incoming = toMap(dto);
        keepInventoryIfIncomingEmpty(existing, incoming);
        keepNotesIfIncomingBlank(existing, incoming);
        applyBuckets(incoming);
        incoming.put("submittedAt", existing.getOrDefault("submittedAt", Instant.now().toString()));
        incoming.put("confirmedAt", Instant.now().toString());
        incoming.put("gate", GATE);
        root.put(KEY, incoming);
        return JsonUtils.toJsonStr(root);
    }

    @SuppressWarnings("unchecked")
    private static void keepInventoryIfIncomingEmpty(Map<String, Object> existing, Map<String, Object> incoming) {
        if (incoming.get("inventory") instanceof List<?> list && !list.isEmpty()) {
            return;
        }
        Object kept = existing.get("inventory");
        incoming.put("inventory", kept instanceof List<?> ? kept : List.of());
    }

    private static void keepNotesIfIncomingBlank(Map<String, Object> existing, Map<String, Object> incoming) {
        Object notes = incoming.get("notes");
        if (notes != null && StringUtils.hasText(String.valueOf(notes))) {
            return;
        }
        if (existing.get("notes") != null) {
            incoming.put("notes", existing.get("notes"));
        }
    }

    /**
     * 用 selected / secret / defer 编号回写清单上的 bucket，未点名的项保持原处置。
     */
    @SuppressWarnings("unchecked")
    static void applyBuckets(Map<String, Object> points) {
        if (!(points.get("inventory") instanceof List<?> inventory) || inventory.isEmpty()) {
            return;
        }
        var selected = toIdSet(points.get("selectedIds"));
        var secrets = toIdSet(points.get("tradeSecretIds"));
        var deferred = toIdSet(points.get("deferredIds"));
        for (Object row : inventory) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            String id = String.valueOf(item.getOrDefault("id", ""));
            if (secrets.contains(id)) {
                item.put("bucket", "secret");
            } else if (deferred.contains(id)) {
                item.put("bucket", "defer");
            } else if (selected.contains(id)) {
                item.put("bucket", "file");
            }
        }
    }

    private static java.util.Set<String> toIdSet(Object raw) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    ids.add(String.valueOf(item).trim());
                }
            }
        }
        return ids;
    }

    private static Map<String, Object> toMap(PkPatentPointsDTO dto) {
        Map<String, Object> points = new LinkedHashMap<>();
        List<Map<String, Object>> inventory = new ArrayList<>();
        if (dto.getInventory() != null) {
            for (PkPatentPointsDTO.Item item : dto.getInventory()) {
                if (item == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId());
                row.put("title", item.getTitle());
                row.put("bucket", item.getBucket());
                row.put("summary", item.getSummary());
                row.put("priorArtNote", item.getPriorArtNote());
                inventory.add(row);
            }
        }
        points.put("inventory", inventory);
        points.put("selectedIds", dto.getSelectedIds() == null ? List.of() : dto.getSelectedIds());
        points.put("tradeSecretIds", dto.getTradeSecretIds() == null ? List.of() : dto.getTradeSecretIds());
        points.put("deferredIds", dto.getDeferredIds() == null ? List.of() : dto.getDeferredIds());
        points.put("notes", dto.getNotes());
        return points;
    }
}
