#!/usr/bin/env python3
"""PatentKing 工具 sidecar 与 runtime 动态工具冒烟测试。"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

PK_TOOLS = os.environ.get("PK_TOOLS_BASE_URL", "http://localhost:3070").rstrip("/")
CONSOLE = os.environ.get("APBOA_CONSOLE_URL", "http://localhost:3060").rstrip("/")
FRONTEND = os.environ.get("APBOA_FRONTEND_URL", "http://localhost").rstrip("/")
USER = os.environ.get("PK_USER", "admin")
PASSWD = os.environ.get("PK_PASS", "Admin@123.com")

PASS = 0
FAIL = 0


def ok(name: str, detail: str = "") -> None:
    global PASS
    PASS += 1
    print(f"  OK  {name}" + (f" — {detail}" if detail else ""))


def bad(name: str, detail: str) -> None:
    global FAIL
    FAIL += 1
    print(f"  FAIL {name}: {detail}")


def http_json(method: str, url: str, body: dict | None = None, headers: dict | None = None, timeout: int = 30):
    data = None
    hdrs = {"Accept": "application/json"}
    if headers:
        hdrs.update(headers)
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        hdrs["Content-Type"] = "application/json; charset=UTF-8"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8", "replace")
        return resp.status, json.loads(raw) if raw.strip() else {}


def login() -> str:
    _, data = http_json(
        "POST",
        f"{CONSOLE}/auth/login",
        {"username": USER, "password": PASSWD},
    )
    token = data.get("data", {}).get("accessToken", "")
    if not token:
        raise RuntimeError(f"登录失败: {data}")
    return token


def runtime_tool(token: str, tool: str, args: dict, timeout: int = 120):
    return http_json(
        "POST",
        f"{FRONTEND}/api/runtime/tool/{tool}/do",
        args,
        headers={"Authorization": f"Bearer {token}"},
        timeout=timeout,
    )


def test_patent_tools_health() -> None:
    print("\n== patent-tools sidecar ==")
    try:
        status, data = http_json("GET", f"{PK_TOOLS}/healthz")
        if status == 200 and data.get("status") == "ok":
            ok("healthz", data.get("service", ""))
        else:
            bad("healthz", str(data))
    except Exception as e:
        bad("healthz", str(e))


def test_prior_art_sidecar() -> None:
    print("\n== pk_prior_art_search (国知局 sidecar) ==")
    try:
        status, data = http_json(
            "POST",
            f"{PK_TOOLS}/v1/cnipa/search",
            {"query": "自然语言 SQL", "limit": 3, "patent_type": "invention"},
            timeout=120,
        )
        if status != 200:
            bad("cnipa/search HTTP", str(status))
            return
        if data.get("ok") is True and data.get("hits"):
            hits = data["hits"]
            src = data.get("source", "")
            cn = sum(1 for h in hits if str(h.get("pub_number", "")).upper().startswith("CN"))
            ok("cnipa/search", f"hits={len(hits)} source={src} cn={cn}")
            if src != "cnipa_epub":
                bad("cnipa/search source", f"expected cnipa_epub, got {src}")
            if cn == 0:
                bad("cnipa/search pub_number", "no CN patent numbers")
        elif data.get("degrade") == "websearch":
            bad("cnipa/search degrade", data.get("message", "")[:200])
        else:
            bad("cnipa/search", json.dumps(data, ensure_ascii=False)[:200])
    except Exception as e:
        bad("cnipa/search", str(e))


def test_runtime_tools(token: str) -> None:
    print("\n== runtime 动态工具 ==")

    try:
        _, resp = runtime_tool(
            token,
            "pk_prior_art_search",
            {"query": "自然语言 SQL", "limit": 3},
            timeout=120,
        )
        if resp.get("success") and resp.get("data", {}).get("ok"):
            hits = resp["data"].get("hits") or []
            src = resp["data"].get("source", "")
            ok("pk_prior_art_search", f"hits={len(hits)} source={src}")
        else:
            bad("pk_prior_art_search", resp.get("msg") or json.dumps(resp, ensure_ascii=False)[:300])
    except Exception as e:
        bad("pk_prior_art_search", str(e))

    inv = json.dumps(
        [{"id": "P1", "title": "测试点", "bucket": "file", "summary": "s", "priorArtNote": ""}],
        ensure_ascii=False,
    )
    try:
        _, resp = runtime_tool(
            token,
            "pk_confirm_patent_points",
            {"inventory_json": inv, "selected_ids": "P1"},
        )
        data = resp.get("data") or {}
        if resp.get("success") and data.get("gate") == "PATENT_POINTS_CONFIRMED":
            ok("pk_confirm_patent_points", data.get("gate", ""))
        else:
            bad("pk_confirm_patent_points", resp.get("msg") or str(data)[:200])
    except Exception as e:
        bad("pk_confirm_patent_points", str(e))

    md = """# 测试交底\n\n```mermaid\nflowchart LR\n    A["用户"] --> B["SQL"]\n```\n"""
    try:
        _, resp = runtime_tool(
            token,
            "pk_export_disclosure",
            {"markdown": md, "title": "pk-smoke", "diagram_mode": "png"},
            timeout=180,
        )
        data = resp.get("data") or {}
        if resp.get("success") and data.get("ok") and data.get("export_id"):
            ok("pk_export_disclosure", f"export_id={data['export_id']}")
        else:
            bad("pk_export_disclosure", resp.get("msg") or json.dumps(data, ensure_ascii=False)[:300])
    except Exception as e:
        bad("pk_export_disclosure", str(e))


def test_export_sidecar() -> None:
    print("\n== export sidecar 直连 ==")
    md = """# 测试交底\n\n```mermaid\nflowchart LR\n    A["用户查询"] --> B["SQL生成"]\n```\n"""
    try:
        status, data = http_json(
            "POST",
            f"{PK_TOOLS}/v1/export/md-to-docx",
            {"markdown": md, "title": "pk-test", "diagram_mode": "png"},
            timeout=120,
        )
        if status == 200 and data.get("ok") and data.get("export_id"):
            ok("export png", f"export_id={data['export_id']}")
        else:
            bad("export png", json.dumps(data, ensure_ascii=False)[:240])
    except Exception as e:
        bad("export png", str(e))


def test_bootstrap_status(token: str) -> None:
    print("\n== PatentKing bootstrap ==")
    try:
        _, data = http_json(
            "GET",
            f"{CONSOLE}/pk/bootstrap/status",
            headers={"Authorization": f"Bearer {token}"},
        )
        if data.get("success"):
            ok("bootstrap/status", str(data.get("data", ""))[:120])
        else:
            bad("bootstrap/status", str(data))
    except Exception as e:
        bad("bootstrap/status", str(e))


def main() -> int:
    print(f"PK_TOOLS={PK_TOOLS}  FRONTEND={FRONTEND}")
    test_patent_tools_health()
    test_prior_art_sidecar()
    test_export_sidecar()
    try:
        token = login()
        ok("login", USER)
        test_runtime_tools(token)
        test_bootstrap_status(token)
    except Exception as e:
        bad("login/runtime", str(e))
    print(f"\n合计: {PASS} 通过, {FAIL} 失败")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
