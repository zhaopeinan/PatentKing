package com.hxh.apboa.pk;

/**
 * 交底正文与专利代理金标准对齐检查（export 前硬门禁）。
 */
public final class PkAlignDisclosureToolCode {

    public static final String TOOL_ID = "pk_align_disclosure";
    public static final String NAME = "交底代理稿对齐";
    public static final String DESC =
            "将交底 Markdown 与专利代理撰写的金标准范本比对（格式、章节、措辞、权利要求结构）。"
                    + "成文后 export 前必须调用；ok=false 时按 checklist 修订后再次调用，直至 gate=DISCLOSURE_STYLE_ALIGNED。"
                    + "对照范本：ground_truth_file/面向单候选文本到SQL转换的双重自适应生成方法.docx";

    public static final String INPUT_SCHEMA = """
            [
              {"name":"markdown","description":"完整交底 Markdown 正文（含权利要求书+说明书五段）","type":"string","required":true,"defaultValue":""}
            ]
            """;

    public static final String SOURCE = """
            import java.util.*;
            import com.hxh.apboa.pk.PkDisclosureStyleSupport;
            import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
            import com.hxh.apboa.engine.agui.AgentContext;
            import org.springframework.stereotype.Component;

            @Component
            public class PkAlignDisclosureTool implements IDynamicAgentTool {
                @Override
                public Object execute(AgentContext context, Map<String, Object> params) {
                    return PkDisclosureStyleSupport.reviewFromTool(params);
                }
            }
            """;

    private PkAlignDisclosureToolCode() {
    }
}
