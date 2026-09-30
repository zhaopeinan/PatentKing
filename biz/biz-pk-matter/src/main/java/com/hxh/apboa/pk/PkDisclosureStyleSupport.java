package com.hxh.apboa.pk;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 交底正文与专利代理金标准（agent-disclosure-style.md）对齐检查。
 */
@Component
public class PkDisclosureStyleSupport {

    private static PkDisclosureStyleSupport self;

    public static final String REFERENCE_DOC =
            "ground_truth_file/面向单候选文本到SQL转换的双重自适应生成方法.docx";

    private static final Pattern CONTACT_NOISE = Pattern.compile(
            "联系人|委托确认|注意事项|立即行动|请自行|手工完成",
            Pattern.CASE_INSENSITIVE);

    private String styleGuide = "";

    @PostConstruct
    void init() {
        self = this;
        try {
            ClassPathResource res = new ClassPathResource("pk/agent-disclosure-style.md");
            if (res.exists()) {
                styleGuide = StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            styleGuide = "";
        }
    }

    public static Map<String, Object> reviewFromTool(Map<String, Object> params) {
        if (self == null) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("message", "PkDisclosureStyleSupport 未初始化");
            failed.put("checklist", List.of());
            return failed;
        }
        String md = str(params, "markdown");
        if (md.isEmpty()) {
            md = str(params, "content");
        }
        return self.review(md);
    }

    public Map<String, Object> review(String markdown) {
        String md = markdown == null ? "" : markdown.trim();
        List<Map<String, Object>> checklist = new ArrayList<>();

        checklist.add(sectionCheck(md, "权利要求书", true,
                "须含编号权利要求；独立项以「1.一种」+「其特征在于」"));
        checklist.add(sectionCheck(md, "技术领域", true,
                "固定句式：本发明涉及……领域，具体涉及一种……"));
        checklist.add(sectionCheck(md, "背景技术", true,
                "客观写现有方案；建议含「但是，上述技术方案」转折"));
        checklist.add(sectionCheck(md, "发明内容", true,
                "须含「针对现有技术存在的不足」与「本发明采用的技术方案如下」"));
        checklist.add(sectionCheck(md, "附图说明", true,
                "逐图说明或「见说明书附图N」"));
        checklist.add(sectionCheck(md, "具体实施方式", true,
                "与权利要求步骤、附图一致，可实施细节"));

        checklist.add(phraseCheck(md, Pattern.compile("1\\.\\s*一种.+其特征在于"),
                true, "独立权利要求格式",
                "1.一种……，其特征在于，包括以下步骤："));
        checklist.add(phraseCheck(md, Pattern.compile("根据权利要求\\d+所述"),
                true, "从属权利要求格式",
                "N.根据权利要求X所述的……，其特征在于，……"));
        checklist.add(phraseCheck(md, Pattern.compile("本发明涉及.+领域.+具体涉及"),
                true, "技术领域开篇",
                "本发明涉及……技术领域，具体涉及一种……。"));
        checklist.add(phraseCheck(md, Pattern.compile("针对现有技术存在的不足"),
                true, "发明内容痛点句",
                "针对现有技术存在的不足，本发明提出一种……"));
        checklist.add(phraseCheck(md, Pattern.compile("本发明采用的技术方案如下"),
                true, "发明内容方案引导",
                "本发明采用的技术方案如下："));
        checklist.add(phraseCheck(md, Pattern.compile("在一些可选的实施方式中"),
                false, "从属/可选实施方式展开",
                "在一些可选的实施方式中，……（与从属权利要求呼应）"));
        checklist.add(phraseCheck(md, Pattern.compile("第一方面，提供了"),
                false, "发明内容第一方面",
                "第一方面，提供了一种……，包括以下步骤："));
        checklist.add(forbiddenCheck(md));

        int pass = 0;
        int fail = 0;
        int warn = 0;
        List<String> mustFix = new ArrayList<>();
        for (Map<String, Object> item : checklist) {
            String level = String.valueOf(item.get("level"));
            boolean ok = Boolean.TRUE.equals(item.get("pass"));
            if (ok) {
                pass++;
            } else if ("required".equals(level)) {
                fail++;
                mustFix.add(String.valueOf(item.get("title")) + "：" + item.get("hint"));
            } else {
                warn++;
            }
        }

        boolean ok = fail == 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("gate", ok ? "DISCLOSURE_STYLE_ALIGNED" : "DISCLOSURE_STYLE_PENDING");
        out.put("message", ok
                ? "交底正文已与专利代理金标准对齐，可进入 Word 导出。"
                : "交底正文尚未与专利代理金标准对齐，请按 checklist 修订后再次调用本工具。");
        out.put("reference_doc", REFERENCE_DOC);
        out.put("style_guide_resource", "pk/agent-disclosure-style.md");
        out.put("checklist", checklist);
        out.put("summary", Map.of(
                "pass", pass,
                "fail", fail,
                "warn", warn,
                "total", checklist.size()));
        out.put("must_fix", mustFix);
        if (StringUtils.hasText(styleGuide)) {
            out.put("style_guide_excerpt", styleGuide.length() > 3500
                    ? styleGuide.substring(0, 3500) + "\n…（完整规范见 style_guide_resource）"
                    : styleGuide);
        }
        out.put("next_step", ok
                ? "若用户已确认 Word，调用 pk_export_disclosure。"
                : "修订 Markdown 后再次调用 pk_align_disclosure，直至 ok=true。");
        return out;
    }

    private static Map<String, Object> sectionCheck(
            String md, String title, boolean required, String hint) {
        boolean pass = containsSection(md, title);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "章节：" + title);
        row.put("pass", pass);
        row.put("level", required ? "required" : "recommended");
        row.put("hint", pass ? "已检测到章节「" + title + "」" : hint);
        row.put("reference", REFERENCE_DOC);
        return row;
    }

    private static boolean containsSection(String md, String title) {
        if (!StringUtils.hasText(md)) {
            return false;
        }
        Pattern p = Pattern.compile(
                "(?m)^#+\\s*" + Pattern.quote(title) + "\\s*$|" + Pattern.quote(title) + "\\s*$");
        return p.matcher(md).find();
    }

    private static Map<String, Object> phraseCheck(
            String md, Pattern pattern, boolean required, String title, String example) {
        boolean pass = StringUtils.hasText(md) && pattern.matcher(md).find();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("pass", pass);
        row.put("level", required ? "required" : "recommended");
        row.put("hint", pass ? "措辞符合代理规范" : "建议采用金标准句式：" + example);
        row.put("reference", REFERENCE_DOC);
        return row;
    }

    private static Map<String, Object> forbiddenCheck(String md) {
        boolean bad = StringUtils.hasText(md) && CONTACT_NOISE.matcher(md).find();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "禁止非技术噪声");
        row.put("pass", !bad);
        row.put("level", "required");
        row.put("hint", bad ? "正文含联系人/委托/平台待办等非技术段落，须删除" : "未检测到非技术噪声");
        row.put("reference", REFERENCE_DOC);
        return row;
    }

    private static String str(Map<String, Object> params, String key) {
        if (params == null) {
            return "";
        }
        Object v = params.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
