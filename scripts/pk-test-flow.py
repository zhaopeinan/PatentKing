#!/usr/bin/env python3
"""PatentKing 案件 API 全流程冒烟（含 main_zh.pdf 附件上传）。"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PDF = ROOT / "test" / "main_zh.pdf"
CONSOLE = os.environ.get("APBOA_CONSOLE_URL", "http://localhost:3060").rstrip("/")
USER = os.environ.get("PK_USER", "admin")
PASSWD = os.environ.get("PK_PASS", "Admin@123.com")

PASS = 0
FAIL = 0
MATTER_ID: int | None = None


def ok(name: str, detail: str = "") -> None:
    global PASS
    PASS += 1
    print(f"  OK  {name}" + (f" — {detail}" if detail else ""))


def bad(name: str, detail: str) -> None:
    global FAIL
    FAIL += 1
    print(f"  FAIL {name}: {detail}")


def http_json(method: str, url: str, body=None, headers=None, timeout=60):
    data = None
    hdrs = {"Accept": "application/json"}
    if headers:
        hdrs.update(headers)
    if body is not None and not isinstance(body, (bytes, bytearray)):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        hdrs["Content-Type"] = "application/json; charset=UTF-8"
    elif isinstance(body, (bytes, bytearray)):
        data = body
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8", "replace")
        return resp.status, json.loads(raw) if raw.strip() else {}


def login() -> str:
    _, data = http_json("POST", f"{CONSOLE}/auth/login", {"username": USER, "password": PASSWD})
    token = data.get("data", {}).get("accessToken", "")
    if not token:
        raise RuntimeError(f"登录失败: {data}")
    return token


def api(token: str, method: str, path: str, body=None, timeout=60):
    return http_json(method, f"{CONSOLE}{path}", body, {"Authorization": f"Bearer {token}"}, timeout)


def upload_pdf(token: str) -> str:
    if not PDF.is_file():
        raise FileNotFoundError(f"测试 PDF 不存在: {PDF}")
    boundary = "----pktestboundary"
    pdf_bytes = PDF.read_bytes()
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="main_zh.pdf"\r\n'
        f"Content-Type: application/pdf\r\n\r\n"
    ).encode() + pdf_bytes + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        f"{CONSOLE}/attach/upload",
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode())
    attach_id = data.get("data")
    if not attach_id:
        raise RuntimeError(f"上传失败: {data}")
    return str(attach_id)


def main() -> int:
    global MATTER_ID
    print(f"CONSOLE={CONSOLE}  PDF={PDF}")

    if not PDF.is_file():
        bad("main_zh.pdf", f"未找到 {PDF}")
        return 1

    try:
        token = login()
        ok("login", USER)
    except Exception as e:
        bad("login", str(e))
        return 1

    try:
        attach_id = upload_pdf(token)
        ok("upload main_zh.pdf", f"attach_id={attach_id}")
    except Exception as e:
        bad("upload main_zh.pdf", str(e))
        return 1

    try:
        _, resp = api(
            token,
            "POST",
            "/pk/matter",
            {
                "title": "冒烟测试-main_zh",
                "matterType": "disclosure",
                "status": "draft",
                "remark": f"attach:{attach_id}",
                "metaJson": json.dumps({"sourceAttachId": attach_id}, ensure_ascii=False),
            },
        )
        if not resp.get("success"):
            bad("create matter", str(resp))
            return 1
        ok("create matter", "saved")
    except Exception as e:
        bad("create matter", str(e))
        return 1

    try:
        _, resp = api(token, "GET", "/pk/matter/page?current=1&size=5")
        records = (resp.get("data") or {}).get("records") or []
        hit = next((r for r in records if r.get("title") == "冒烟测试-main_zh"), None)
        if not hit:
            bad("list matter", "未找到新建案件")
            return 1
        MATTER_ID = int(hit["id"])
        ok("list matter", f"id={MATTER_ID}")
    except Exception as e:
        bad("list matter", str(e))
        return 1

    inv = [
        {
            "id": "P1",
            "title": "Text-to-SQL 模式增强",
            "bucket": "file",
            "summary": "基于 main_zh 论文的 NL2SQL 方法",
            "priorArtNote": "",
        }
    ]
    try:
        _, resp = api(
            token,
            "POST",
            f"/pk/matter/{MATTER_ID}/patent-points",
            {"inventory": inv, "selectedIds": ["P1"]},
        )
        if resp.get("success"):
            ok("submit patent-points", "inventory saved")
        else:
            bad("submit patent-points", str(resp))
    except Exception as e:
        bad("submit patent-points", str(e))

    try:
        _, resp = api(
            token,
            "POST",
            f"/pk/matter/{MATTER_ID}/patent-points/confirm",
            {"selectedIds": ["P1"]},
        )
        if resp.get("success"):
            ok("confirm patent-points", "gate passed")
        else:
            bad("confirm patent-points", str(resp))
    except Exception as e:
        bad("confirm patent-points", str(e))

    try:
        _, resp = api(
            token,
            "POST",
            f"/pk/matter/{MATTER_ID}/delivery",
            {"format": "word", "diagramMode": "png", "notes": "smoke test"},
        )
        if resp.get("success"):
            ok("submit delivery", "word/png")
        else:
            bad("submit delivery", str(resp))
    except Exception as e:
        bad("submit delivery", str(e))

    md = """# 交底冒烟\n\n## 技术方案\n\n基于 main_zh 论文。\n\n```mermaid\nflowchart LR\n    Q["自然语言"] --> M["模式增强"] --> S["SQL"]\n```\n"""
    try:
        _, resp = api(
            token,
            "POST",
            f"/pk/matter/{MATTER_ID}/export-docx",
            {"markdown": md, "title": "main-zh-smoke", "diagramMode": "png"},
            timeout=180,
        )
        data = resp.get("data") or {}
        if resp.get("success") and data.get("id"):
            ok("export-docx", f"artifact={data.get('id')}")
        else:
            bad("export-docx", resp.get("msg") or str(data)[:200])
    except Exception as e:
        bad("export-docx", str(e))

    try:
        _, resp = api(token, "GET", f"/pk/matter/{MATTER_ID}/artifacts")
        arts = resp.get("data") or []
        ok("list artifacts", f"count={len(arts)}")
    except Exception as e:
        bad("list artifacts", str(e))

    print(f"\n案件 ID: {MATTER_ID}")
    print(f"合计: {PASS} 通过, {FAIL} 失败")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
