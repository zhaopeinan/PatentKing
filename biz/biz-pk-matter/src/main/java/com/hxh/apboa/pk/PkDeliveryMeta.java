package com.hxh.apboa.pk;

import com.hxh.apboa.common.dto.PkDeliveryDTO;
import com.hxh.apboa.common.util.JsonUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交付格式选择写入 pk_matter.meta_json.delivery。
 */
public final class PkDeliveryMeta {

    public static final String KEY = "delivery";

    private PkDeliveryMeta() {
    }

    public static Map<String, Object> readDelivery(String metaJson) {
        Object raw = PkPatentPointsMeta.readRoot(metaJson).get(KEY);
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
            return copy;
        }
        return new LinkedHashMap<>();
    }

    public static String merge(String metaJson, PkDeliveryDTO dto) {
        Map<String, Object> root = PkPatentPointsMeta.readRoot(metaJson);
        Map<String, Object> delivery = toMap(dto);
        delivery.put("submittedAt", Instant.now().toString());
        root.put(KEY, delivery);
        return JsonUtils.toJsonStr(root);
    }

    private static Map<String, Object> toMap(PkDeliveryDTO dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (dto == null) {
            return map;
        }
        if (StringUtils.hasText(dto.getPreviewAction())) {
            map.put("previewAction", dto.getPreviewAction().trim());
        }
        if (StringUtils.hasText(dto.getAdjustNotes())) {
            map.put("adjustNotes", dto.getAdjustNotes().trim());
        }
        if (StringUtils.hasText(dto.getDeliveryFormat())) {
            map.put("deliveryFormat", dto.getDeliveryFormat().trim().toLowerCase());
        }
        if (StringUtils.hasText(dto.getDiagramMode())) {
            map.put("diagramMode", dto.getDiagramMode().trim().toLowerCase());
        }
        if (dto.getIncludePdf() != null) {
            map.put("includePdf", dto.getIncludePdf());
        }
        return map;
    }
}
