package com.hxh.apboa.pk;

/**
 * 交底 Markdown → 机器渲染 mermaid PNG → Word。
 * 调用 patent-tools /v1/export/md-to-docx；禁止让用户手工渲染图示。
 */
public final class PkExportDisclosureToolCode {

    public static final String TOOL_ID = "pk_export_disclosure";
    public static final String NAME = "交底导出Word";
    public static final String DESC =
            "把交底 Markdown（含 mermaid）机器渲染为 PNG 并生成 Word。"
                    + "须在 pk_align_disclosure 返回 ok=true 之后调用。"
                    + "用户选择 Word 或 PNG 本地渲染时必须调用本工具，禁止要求用户自己渲染 mermaid。"
                    + "成功返回 export_id、filename、figure_count；前端/案件页会据此登记产物。";

    public static final String INPUT_SCHEMA = """
            [
              {"name":"markdown","description":"完整交底 Markdown 正文，须含 mermaid 图","type":"string","required":true,"defaultValue":""},
              {"name":"title","description":"输出文件名前缀，如 NL2SQL交底","type":"string","required":false,"defaultValue":"disclosure"},
              {"name":"diagram_mode","description":"png（默认，mmdc 机器渲染）| auto（Tokenlab 自动生成，需先在系统设置配置）","type":"string","required":false,"defaultValue":"png"},
              {"name":"matter_id","description":"案件 ID，从案件打开对话时尽量带上","type":"string","required":false,"defaultValue":""}
            ]
            """;

            public static final String SOURCE = """
            import java.util.*;
            import com.hxh.apboa.pk.PkExportSupport;
            import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
            import com.hxh.apboa.engine.agui.AgentContext;
            import org.springframework.stereotype.Component;

            @Component
            public class PkExportDisclosureTool implements IDynamicAgentTool {
                @Override
                public Object execute(AgentContext context, Map<String, Object> params) {
                    Map<String, Object> ctxParams = context != null ? context.getParams() : null;
                    return PkExportSupport.exportFromTool(params, ctxParams);
                }
            }
            """;

    private PkExportDisclosureToolCode() {
    }
}
