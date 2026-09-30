package com.hxh.apboa.pk;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 案件类型 → 默认智能体编码。
 * <p>
 * 一级类型只保留有独立流水线的：交底 / 论文转专利 / 侵权 / 审查答复。
 * 解读、估值、尽调、供需等作为交底下的「场景」，由对话意图或建案场景字段区分。
 */
public final class PkMatterAgentCodes {

    public static final String DISCLOSURE = "pk-disclosure";
    public static final String PAPER2PATENT = "pk-paper2patent";
    public static final String RADAR = "pk-radar";
    public static final String DISCLOSURE_LITE = "pk-disclosure-lite";

    /** 建案可选的一级类型 */
    public static final List<String> PRIMARY_TYPES = List.of(
            "disclosure", "paper2patent", "radar", "oa"
    );

    /** 历史类型，展示时归并到 disclosure（或仍按映射选 Agent） */
    public static final Set<String> LEGACY_DISCLOSURE_SCENES = Set.of(
            "read", "valuate", "dd", "match"
    );

    private static final Map<String, String> TYPE_TO_CODE = Map.of(
            "disclosure", DISCLOSURE,
            "paper2patent", PAPER2PATENT,
            "radar", RADAR,
            "oa", DISCLOSURE,
            // legacy
            "read", DISCLOSURE,
            "valuate", DISCLOSURE,
            "dd", DISCLOSURE,
            "match", DISCLOSURE
    );

    private static final Map<String, String> SCENE_TO_CODE = Map.of(
            "oral", DISCLOSURE_LITE,
            "write", DISCLOSURE,
            "read", DISCLOSURE,
            "valuate", DISCLOSURE,
            "dd", DISCLOSURE,
            "match", DISCLOSURE,
            "oa", DISCLOSURE
    );

    private PkMatterAgentCodes() {
    }

    /**
     * 归一化一级类型：旧细分类写入 meta.scene，matterType 收敛到 disclosure。
     */
    public static String normalizeMatterType(String matterType) {
        if (matterType == null || matterType.isBlank()) {
            return "disclosure";
        }
        String t = matterType.trim();
        if (LEGACY_DISCLOSURE_SCENES.contains(t)) {
            return "disclosure";
        }
        if (PRIMARY_TYPES.contains(t)) {
            return t;
        }
        return "disclosure";
    }

    /**
     * 从旧 matterType 推断场景（仅当未显式传 scene）。
     */
    public static String inferSceneFromLegacyType(String rawMatterType) {
        if (rawMatterType == null || rawMatterType.isBlank()) {
            return "write";
        }
        String t = rawMatterType.trim();
        if (LEGACY_DISCLOSURE_SCENES.contains(t)) {
            return t;
        }
        if ("oa".equals(t)) {
            return "oa";
        }
        return "write";
    }

    public static String codeForMatterType(String matterType) {
        return codeForMatterType(matterType, null);
    }

    public static String codeForMatterType(String matterType, String scene) {
        if (scene != null && !scene.isBlank()) {
            String byScene = SCENE_TO_CODE.get(scene.trim());
            if (byScene != null) {
                return byScene;
            }
        }
        if (matterType == null || matterType.isBlank()) {
            return DISCLOSURE;
        }
        return TYPE_TO_CODE.getOrDefault(matterType.trim(), DISCLOSURE);
    }

    /** 筛选「交底撰写」时包含历史细分类 */
    public static List<String> disclosureFamilyTypes() {
        return List.of("disclosure", "read", "valuate", "dd", "match");
    }
}
