package com.hxh.apboa.pk;

/**
 * 自定义查新工具（Groovy 源码，language=JAVA）。
 * 调用 patent-tools sidecar /v1/cnipa/search。
 */
public final class PkPriorArtToolCode {

    public static final String TOOL_ID = "pk_prior_art_search";
    public static final String NAME = "专利公开检索";
    public static final String DESC =
            "中国专利查新（优先国知局公布公告站 epub.cnipa.gov.cn，不可达时自动降级 Google Patents）。"
                    + "每次只传一个短语义块（如「自然语言转SQL」或「NL2SQL」），禁止用空格拼多个无关词；"
                    + "多词会被站内 AND，极易 0 条。返回 CN 公开号、标题、摘要与详情链接。"
                    + "仅当国知局与 Google Patents 均失败时 degrade=websearch，请标明未完成公开检索。";

    public static final String SOURCE = """
            import java.util.*;
            import com.hxh.apboa.pk.PkPriorArtSupport;
            import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
            import com.hxh.apboa.engine.agui.AgentContext;
            import org.springframework.stereotype.Component;

            @Component
            public class PkPriorArtSearchTool implements IDynamicAgentTool {
                @Override
                public Object execute(AgentContext context, Map<String, Object> params) {
                    return PkPriorArtSupport.searchFromTool(params);
                }
            }
            """;

    private PkPriorArtToolCode() {
    }
}
