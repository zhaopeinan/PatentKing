# 专利代理交底金标准

本目录在本地存放 **专利代理人撰写** 的交底/申请文本原文，供 PatentKing 生成结果对齐使用。这些范文是业务数据，不进入公开仓库；克隆后请自行放回对应 `.docx`，文件名与下表一致即可。

## 当前范本

| 文件 | 用途 |
|------|------|
| `面向单候选文本到SQL转换的双重自适应生成方法.docx` | NL2SQL 主题金标准：权利要求结构 + 说明书五段式 + 代理固定句式 |

平台规范说明（Agent / 工具可读）：`biz/biz-pk-matter/src/main/resources/pk/agent-disclosure-style.md`

## 对齐流程

1. Agent 完成交底 Markdown 初稿  
2. 调用工具 **`pk_align_disclosure`** 自动检查章节、措辞、权利要求格式  
3. 通过后再调用 **`pk_export_disclosure`** 导出 Word  

新增主题时，可在此目录追加代理稿 `.docx`，并更新 `agent-disclosure-style.md` 中的示例句（结构模板保持不变）。
