#!/usr/bin/env python3
"""Seed PatentKing system prompt templates into MySQL (tenant 1)."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "biz/biz-pk-matter/src/main/java/com/hxh/apboa/pk/PkPromptTemplates.java"
MYSQL = [
    "docker", "exec", "-i", "apboa-mysql",
    "mysql", "-uroot", "-proot", "--default-character-set=utf8mb4", "apboa_next",
]

AGENT_BIND = {
    "pk-disclosure": "PatentKing 交底助手",
    "pk-paper2patent": "PatentKing 论文转五书",
    "pk-radar": "PatentKing 侵权分析",
    "pk-disclosure-lite": "PatentKing 口述交底",
}


def parse_java_constants(text: str) -> dict[str, str]:
    consts: dict[str, str] = {}
    for m in re.finditer(
        r'public static final String (\w+) = """\n(.*?)"""\s*;',
        text,
        re.S,
    ):
        body = m.group(2)
        lines = body.splitlines()
        stripped = []
        for line in lines:
            stripped.append(re.sub(r"^            ", "", line) if line.startswith("            ") else line)
        consts[m.group(1)] = "\n".join(stripped).strip("\n") + "\n"
    for m in re.finditer(r'public static final String (\w+) = "([^"]+)";', text):
        consts[m.group(1)] = m.group(2)
    return consts


def sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def mysql(sql: str) -> str:
    r = subprocess.run(MYSQL, input=sql.encode("utf-8"), capture_output=True)
    if r.returncode != 0:
        raise SystemExit((r.stderr or r.stdout).decode("utf-8", "replace"))
    return r.stdout.decode("utf-8", "replace")


def main() -> None:
    consts = parse_java_constants(JAVA.read_text(encoding="utf-8"))
    category = consts["CATEGORY"]
    rows = [
        ("DISCLOSURE_NAME", "DISCLOSURE_DESC", "DISCLOSURE_CONTENT"),
        ("PAPER2PATENT_NAME", "PAPER2PATENT_DESC", "PAPER2PATENT_CONTENT"),
        ("RADAR_NAME", "RADAR_DESC", "RADAR_CONTENT"),
        ("DISCLOSURE_LITE_NAME", "DISCLOSURE_LITE_DESC", "DISCLOSURE_LITE_CONTENT"),
    ]
    ids = [2090100000000000001 + i for i in range(len(rows))]
    values = []
    for i, (nk, dk, ck) in enumerate(rows):
        values.append(
            "("
            + ",".join(
                [
                    str(ids[i]),
                    sql_quote(category),
                    sql_quote(consts[nk]),
                    sql_quote(consts[dk]),
                    sql_quote(consts[ck]),
                    "1",
                    "0",
                    "NOW()",
                    "NOW()",
                    "1111111111111111111",
                    "1111111111111111111",
                    "1",
                ]
            )
            + ")"
        )
    mysql(
        "INSERT INTO system_prompt_template "
        "(id, category, name, description, content, enabled, usage_count, "
        "created_at, updated_at, created_by, updated_by, tenant_id) VALUES "
        + ",".join(values)
        + " ON DUPLICATE KEY UPDATE description=VALUES(description), "
        "content=VALUES(content), enabled=1, updated_at=NOW();"
    )
    for i, (nk, _, _) in enumerate(rows):
        print(f"upserted {consts[nk]} id={ids[i]}")

    for code, name in AGENT_BIND.items():
        mysql(
            "UPDATE agent_definition a "
            "JOIN system_prompt_template t "
            "  ON t.name=" + sql_quote(name) + " AND t.category=" + sql_quote(category) + " AND t.tenant_id=a.tenant_id "
            "SET a.system_prompt_template_id=t.id, a.follow_template=1, a.system_prompt=t.content "
            "WHERE a.agent_code=" + sql_quote(code) + ";"
        )
        print(f"bound {code} -> {name}")

    print(mysql(
        "SELECT id, name, category, CHAR_LENGTH(content) AS clen, enabled FROM system_prompt_template;\n"
        "SELECT agent_code, system_prompt_template_id, follow_template FROM agent_definition;"
    ))


if __name__ == "__main__":
    main()
