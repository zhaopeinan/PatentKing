#!/usr/bin/env python3
"""Bind PatentKing agents to the connected model, prior-art search, and patent-point confirm tools."""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRIOR_JAVA = ROOT / "biz/biz-pk-matter/src/main/java/com/hxh/apboa/pk/PkPriorArtToolCode.java"
CONFIRM_JAVA = ROOT / "biz/biz-pk-matter/src/main/java/com/hxh/apboa/pk/PkConfirmPatentPointsToolCode.java"
EXPORT_JAVA = ROOT / "biz/biz-pk-matter/src/main/java/com/hxh/apboa/pk/PkExportDisclosureToolCode.java"
ALIGN_JAVA = ROOT / "biz/biz-pk-matter/src/main/java/com/hxh/apboa/pk/PkAlignDisclosureToolCode.java"
MYSQL = [
    "docker", "exec", "-i", "apboa-mysql",
    "mysql", "-uroot", "-proot", "-N", "--default-character-set=utf8mb4", "apboa_next",
]
PRIOR_TOOL_ID_NUM = 2090100000000000010
CONFIRM_TOOL_ID_NUM = 2090100000000000011
EXPORT_TOOL_ID_NUM = 2090100000000000012
ALIGN_TOOL_ID_NUM = 2090100000000000013
AGENT_CODES = (
    "pk-disclosure",
    "pk-paper2patent",
    "pk-radar",
    "pk-disclosure-lite",
)
CONFIRM_AGENT_CODES = (
    "pk-disclosure",
    "pk-disclosure-lite",
)
EXPORT_AGENT_CODES = (
    "pk-disclosure",
    "pk-disclosure-lite",
    "pk-paper2patent",
)
PRIOR_SCHEMA = json.dumps(
    [
        {
            "name": "query",
            "description": "单个短语义块，如 NL2SQL 或 自然语言转SQL；禁止空格拼多个词（站内 AND 易 0 条）",
            "type": "string",
            "required": True,
            "defaultValue": "",
        },
        {
            "name": "limit",
            "description": "返回条数，默认 8，最大 20",
            "type": "integer",
            "required": False,
            "defaultValue": "8",
        },
    ],
    ensure_ascii=False,
)
CONFIRM_SCHEMA = json.dumps(
    [
        {
            "name": "inventory_json",
            "description": "专利点资产清单 JSON 数组，每项含 id,title,bucket(file|secret|defer),summary,priorArtNote",
            "type": "string",
            "required": True,
            "defaultValue": "",
        },
        {
            "name": "selected_ids",
            "description": "拟写入交底的点编号，逗号分隔或 JSON 数组，如 P1,P3",
            "type": "string",
            "required": True,
            "defaultValue": "",
        },
        {
            "name": "trade_secret_ids",
            "description": "商业秘密、禁止写入交底的点编号",
            "type": "string",
            "required": False,
            "defaultValue": "",
        },
        {
            "name": "deferred_ids",
            "description": "暂缓申请的点编号",
            "type": "string",
            "required": False,
            "defaultValue": "",
        },
        {
            "name": "notes",
            "description": "用户补充意见",
            "type": "string",
            "required": False,
            "defaultValue": "",
        },
        {
            "name": "matter_id",
            "description": "案件 ID，从案件打开对话时尽量带上",
            "type": "string",
            "required": False,
            "defaultValue": "",
        },
    ],
    ensure_ascii=False,
)
EXPORT_SCHEMA = json.dumps(
    [
        {
            "name": "markdown",
            "description": "完整交底 Markdown 正文，须含 mermaid 图",
            "type": "string",
            "required": True,
            "defaultValue": "",
        },
        {
            "name": "title",
            "description": "输出文件名前缀，如 NL2SQL交底",
            "type": "string",
            "required": False,
            "defaultValue": "disclosure",
        },
        {
            "name": "diagram_mode",
            "description": "png（默认，mmdc 机器渲染）| auto（Tokenlab 自动生成，需先在系统设置配置）",
            "type": "string",
            "required": False,
            "defaultValue": "png",
        },
        {
            "name": "matter_id",
            "description": "案件 ID，从案件打开对话时尽量带上",
            "type": "string",
            "required": False,
            "defaultValue": "",
        },
    ],
    ensure_ascii=False,
)
ALIGN_SCHEMA = json.dumps(
    [
        {
            "name": "markdown",
            "description": "完整交底 Markdown 正文（含权利要求书+说明书五段）",
            "type": "string",
            "required": True,
            "defaultValue": "",
        },
    ],
    ensure_ascii=False,
)


def sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def mysql(sql: str) -> str:
    r = subprocess.run(MYSQL, input=sql.encode("utf-8"), capture_output=True)
    if r.returncode != 0:
        raise SystemExit((r.stderr or r.stdout).decode("utf-8", "replace"))
    return r.stdout.decode("utf-8", "replace")


def parse_source(text: str) -> str:
    m = re.search(r'public static final String SOURCE = """\n(.*?)"""\s*;', text, re.S)
    if not m:
        raise SystemExit("SOURCE text block not found")
    lines = []
    for line in m.group(1).splitlines():
        lines.append(re.sub(r"^            ", "", line) if line.startswith("            ") else line)
    return "\n".join(lines).strip("\n") + "\n"


def upsert_tool(
    numeric_id: int,
    name: str,
    tool_id: str,
    description: str,
    schema: str,
    source: str,
    need_confirm: bool,
) -> None:
    mysql(
        "INSERT INTO tool_config "
        "(id, name, tool_id, description, category, tool_type, input_schema, output_schema, "
        "class_path, language, code, need_confirm, enabled, version, created_at, updated_at, "
        "created_by, updated_by, tenant_id, scope_type) VALUES ("
        + ",".join(
            [
                str(numeric_id),
                sql_quote(name),
                sql_quote(tool_id),
                sql_quote(description),
                sql_quote("pk"),
                sql_quote("CUSTOM"),
                sql_quote(schema),
                "NULL",
                "NULL",
                sql_quote("JAVA"),
                sql_quote(source),
                "1" if need_confirm else "0",
                "1",
                sql_quote("1.0"),
                "NOW()",
                "NOW()",
                "1111111111111111111",
                "1111111111111111111",
                "1",
                sql_quote("TENANT"),
            ]
        )
        + ") ON DUPLICATE KEY UPDATE description=VALUES(description), code=VALUES(code), "
        "input_schema=VALUES(input_schema), need_confirm=VALUES(need_confirm), "
        "name=VALUES(name), enabled=1, updated_at=NOW();"
    )


def bind_tool(bind_id_start: int, tool_id: str, agent_codes: tuple[str, ...]) -> None:
    for i, code in enumerate(agent_codes):
        mysql(
            "INSERT INTO agent_tools (id, agent_definition_id, tool_id, tenant_id) "
            "SELECT "
            + str(bind_id_start + i)
            + ", a.id, t.id, a.tenant_id FROM agent_definition a "
            "JOIN tool_config t ON t.tool_id=" + sql_quote(tool_id) + " AND t.tenant_id=a.tenant_id "
            "WHERE a.agent_code=" + sql_quote(code) + " "
            "AND NOT EXISTS ("
            "  SELECT 1 FROM agent_tools x "
            "  WHERE x.agent_definition_id=a.id AND x.tool_id=t.id"
            ");"
        )
        print(f"bound {tool_id} -> {code}")


def main() -> None:
    mid = mysql(
        "SELECT id FROM model_config WHERE enabled=1 AND connectivity_status='CONNECTED' "
        "ORDER BY id DESC LIMIT 1;"
    ).strip().split("\n")[-1].strip()
    if not mid.isdigit():
        raise SystemExit("没有已连通的模型，请先在「模型」里测试连接成功。")
    n = mysql(
        f"UPDATE agent_definition SET model_config_id={mid} "
        f"WHERE agent_code IN ({','.join(sql_quote(c) for c in AGENT_CODES)}) "
        "AND (model_config_id IS NULL OR model_config_id<>" + mid + ");"
        "SELECT ROW_COUNT();"
    )
    print(f"bound model {mid} (row_count output follows)\n{n}")

    mysql(
        "UPDATE agent_definition SET show_tool_process=1 "
        f"WHERE agent_code IN ({','.join(sql_quote(c) for c in AGENT_CODES)});"
    )
    print("enabled show_tool_process for PatentKing agents")

    upsert_tool(
        PRIOR_TOOL_ID_NUM,
        "专利公开检索",
        "pk_prior_art_search",
        "中国专利查新（国知局公布公告站）。每次只传一个短语义块（如 NL2SQL、自然语言转SQL），禁止空格拼多词；"
        "返回 CN 公开号、标题、摘要与国知局链接。失败时 degrade=websearch。",
        PRIOR_SCHEMA,
        parse_source(PRIOR_JAVA.read_text(encoding="utf-8")),
        False,
    )
    print("upserted tool pk_prior_art_search")
    bind_tool(2090100000000000100, "pk_prior_art_search", AGENT_CODES)

    upsert_tool(
        CONFIRM_TOOL_ID_NUM,
        "专利点确认闸门",
        "pk_confirm_patent_points",
        "交底成文硬门禁。扫描出专利点后必须调用本工具并等待用户在对话中点「允许」。"
        "传入完整资产清单 JSON 以及拟申请/商业秘密/暂缓编号。"
        "用户允许后返回 gate=PATENT_POINTS_CONFIRMED，此时才允许写交底正文。用户禁止则不得成文。",
        CONFIRM_SCHEMA,
        parse_source(CONFIRM_JAVA.read_text(encoding="utf-8")),
        True,
    )
    print("upserted tool pk_confirm_patent_points")
    bind_tool(2090100000000000110, "pk_confirm_patent_points", CONFIRM_AGENT_CODES)

    upsert_tool(
        ALIGN_TOOL_ID_NUM,
        "交底代理稿对齐",
        "pk_align_disclosure",
        "将交底 Markdown 与专利代理金标准范本比对（格式、章节、措辞、权利要求结构）。"
        "成文后 export 前必须调用；ok=false 时按 checklist 修订后再次调用。",
        ALIGN_SCHEMA,
        parse_source(ALIGN_JAVA.read_text(encoding="utf-8")),
        False,
    )
    print("upserted tool pk_align_disclosure")
    bind_tool(2090100000000000130, "pk_align_disclosure", CONFIRM_AGENT_CODES)

    upsert_tool(
        EXPORT_TOOL_ID_NUM,
        "交底导出Word",
        "pk_export_disclosure",
        "把交底 Markdown（含 mermaid）机器渲染为 PNG 并生成 Word。"
        "用户选择 Word 或 PNG 本地渲染时必须调用本工具，禁止要求用户自己渲染 mermaid。"
        "成功返回 export_id、filename、figure_count；前端/案件页会据此登记产物。",
        EXPORT_SCHEMA,
        parse_source(EXPORT_JAVA.read_text(encoding="utf-8")),
        False,
    )
    print("upserted tool pk_export_disclosure")
    bind_tool(2090100000000000120, "pk_export_disclosure", EXPORT_AGENT_CODES)

    print(mysql(
        "SELECT agent_code, model_config_id FROM agent_definition;\n"
        "SELECT a.agent_code, t.tool_id, t.need_confirm FROM agent_tools x "
        "JOIN agent_definition a ON a.id=x.agent_definition_id "
        "JOIN tool_config t ON t.id=x.tool_id;"
    ))


if __name__ == "__main__":
    main()
