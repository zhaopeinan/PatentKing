package com.hxh.apboa.pk;

/**
 * 专利点确认闸门（Groovy/Java 自定义工具，needConfirm=true）。
 * 平台会在执行前暂停对话，等用户点「允许」后才返回 PATENT_POINTS_CONFIRMED。
 */
public final class PkConfirmPatentPointsToolCode {

    public static final String TOOL_ID = "pk_confirm_patent_points";
    public static final String NAME = "专利点确认闸门";
    public static final String DESC =
            "交底成文硬门禁。扫描出专利点后必须调用本工具并等待用户在对话中点「允许」。"
                    + "传入完整资产清单 JSON 以及拟申请/商业秘密/暂缓编号。"
                    + "用户允许后返回 gate=PATENT_POINTS_CONFIRMED，此时才允许写交底正文。"
                    + "用户禁止则不得成文。";

    public static final String INPUT_SCHEMA = """
            [
              {"name":"inventory_json","description":"专利点资产清单 JSON 数组，每项含 id,title,bucket(file|secret|defer),summary,priorArtNote","type":"string","required":true,"defaultValue":""},
              {"name":"selected_ids","description":"拟写入交底的点编号，逗号分隔或 JSON 数组，如 P1,P3","type":"string","required":true,"defaultValue":""},
              {"name":"trade_secret_ids","description":"商业秘密、禁止写入交底的点编号","type":"string","required":false,"defaultValue":""},
              {"name":"deferred_ids","description":"暂缓申请的点编号","type":"string","required":false,"defaultValue":""},
              {"name":"notes","description":"用户补充意见","type":"string","required":false,"defaultValue":""},
              {"name":"matter_id","description":"案件 ID，从案件打开对话时尽量带上","type":"string","required":false,"defaultValue":""}
            ]
            """;

    public static final String SOURCE = """
            import java.util.*;
            import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
            import com.hxh.apboa.engine.agui.AgentContext;
            import org.springframework.stereotype.Component;

            @Component
            public class PkConfirmPatentPointsTool implements IDynamicAgentTool {
                @Override
                public Object execute(AgentContext context, Map<String, Object> params) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    String inventory = str(params, "inventory_json");
                    if (inventory.isEmpty()) {
                        out.put("ok", false);
                        out.put("gate", "missing_inventory");
                        out.put("message", "inventory_json 为空，无法确认专利点");
                        return out;
                    }
                    String selected = str(params, "selected_ids");
                    if (selected.isEmpty()) {
                        out.put("ok", false);
                        out.put("gate", "missing_selected");
                        out.put("message", "selected_ids 为空。用户必须明确选择要写入交底的点，不能整单默认全写。");
                        return out;
                    }
                    String secrets = str(params, "trade_secret_ids");
                    String deferred = str(params, "deferred_ids");
                    String notes = str(params, "notes");
                    String matterId = str(params, "matter_id");
                    if (matterId.isEmpty() && context != null && context.getParams() != null) {
                        Object mid = context.getParams().get("matterId");
                        if (mid == null) {
                            mid = context.getParams().get("matter_id");
                        }
                        if (mid != null) {
                            matterId = String.valueOf(mid).trim();
                        }
                    }
                    out.put("ok", true);
                    out.put("gate", "PATENT_POINTS_CONFIRMED");
                    out.put("matter_id", matterId);
                    out.put("selected_ids", selected);
                    out.put("trade_secret_ids", secrets);
                    out.put("deferred_ids", deferred);
                    out.put("notes", notes);
                    out.put("inventory_json", inventory);
                    out.put("message", "用户已确认专利点闸门。只对 selected_ids 成文；trade_secret_ids 视为商业秘密不得写入交底；deferred_ids 暂缓。");
                    return out;
                }

                private static String str(Map<String, Object> params, String key) {
                    if (params == null) {
                        return "";
                    }
                    Object v = params.get(key);
                    return v == null ? "" : String.valueOf(v).trim();
                }
            }
            """;

    private PkConfirmPatentPointsToolCode() {
    }
}
