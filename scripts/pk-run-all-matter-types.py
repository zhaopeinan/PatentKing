#!/usr/bin/env python3
"""真实跑通 PatentKing 全部案件类型示例，便于在 UI 逐个打开查看。

类型：disclosure / paper2patent / read / oa / radar / valuate / dd / match
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PDF = ROOT / "test" / "main_zh.pdf"
CONSOLE = os.environ.get("APBOA_CONSOLE_URL", "http://127.0.0.1:3060").rstrip("/")
# 网关可能把 /api 转到 console；AGUI 走 runtime
GATEWAY = os.environ.get("APBOA_GATEWAY_URL", "http://127.0.0.1").rstrip("/")
USER = os.environ.get("PK_USER", "admin")
PASSWD = os.environ.get("PK_PASS", "Admin@123.com")
RUN_TIMEOUT = int(os.environ.get("PK_RUN_TIMEOUT", "720"))  # 每案最长秒数

TYPE_SPECS = [
    {
        "matterType": "disclosure",
        "scene": "write",
        "title": "【示例】交底撰写-Text2SQL",
        "agentCode": "pk-disclosure",
        "needPdf": True,
        "prompt": (
            "这是 PatentKing「交底撰写」示例。素材是 Text-to-SQL / SAGE-SQL。\n"
            "请轻量跑通：挖 2～3 个专利点；对一点调用 pk_prior_art_search（关键词「自然语言转SQL」）；"
            "给出含 1 个 mermaid 的交底小节；尽量调用确认与导出工具，不要干等用户。\n"
        ),
    },
    {
        "matterType": "paper2patent",
        "scene": "write",
        "title": "【示例】论文转专利-main_zh",
        "agentCode": "pk-paper2patent",
        "needPdf": True,
        "prompt": (
            "这是「论文转专利」示例。附件为论文 PDF。\n"
            "请轻量：概括贡献、列 2 个发明点、输出简短交底 Markdown；可用则 pk_export_disclosure。\n"
        ),
    },
    {
        "matterType": "radar",
        "scene": "write",
        "title": "【示例】侵权分析-NL2SQL产品对照",
        "agentCode": "pk-radar",
        "needPdf": False,
        "prompt": (
            "这是「侵权分析」示例。我方：企业内网 Text-to-SQL 助手。\n"
            "请轻量：分解特征、可检索则 pk_prior_art_search（「自然语言转SQL」）、风险分级与规避建议。\n"
        ),
    },
    {
        "matterType": "oa",
        "scene": "oa",
        "title": "【示例】审查答复-创造性答辩提纲",
        "agentCode": "pk-disclosure",
        "needPdf": False,
        "prompt": (
            "这是「审查答复」示例。假设审查意见认为 NL2SQL+模式检索无创造性。\n"
            "请给答复提纲（争点、区别特征、效果、修改方向），可检索「SQL生成」。输出 Markdown。\n"
        ),
    },
    {
        "matterType": "disclosure",
        "scene": "read",
        "title": "【示例】交底·场景解读-CN122594317A",
        "agentCode": "pk-disclosure",
        "needPdf": False,
        "prompt": (
            "场景=专利解读。请解读 CN122594317A，输出问题/方案/保护印象/启示 Markdown，勿强行成文闸门。\n"
        ),
    },
]


def http_json(method: str, url: str, body=None, headers=None, timeout: int = 120):
    data = None
    hdrs = {"Accept": "application/json"}
    if headers:
        hdrs.update(headers)
    if body is not None and not isinstance(body, (bytes, bytearray)):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json; charset=UTF-8")
    elif isinstance(body, (bytes, bytearray)):
        data = body
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8", "replace")
        return resp.status, json.loads(raw) if raw.strip() else {}


def login() -> str:
    _, data = http_json("POST", f"{CONSOLE}/auth/login", {"username": USER, "password": PASSWD})
    token = (data.get("data") or {}).get("accessToken") or ""
    if not token:
        raise RuntimeError(f"登录失败: {data}")
    return token


def api(token: str, method: str, path: str, body=None, timeout: int = 120):
    # console 业务接口统一走 /api 前缀（与前端一致）
    url = f"{CONSOLE}{path}" if path.startswith("/api/") else f"{CONSOLE}/api{path}"
    return http_json(method, url, body, {"Authorization": f"Bearer {token}"}, timeout=timeout)


def upload_pdf(token: str) -> str:
    if not PDF.is_file():
        raise FileNotFoundError(f"缺少 {PDF}")
    boundary = "----pkalltypes"
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


def bootstrap_agents(token: str) -> dict[str, str]:
    _, resp = api(token, "POST", "/pk/bootstrap/agents")
    data = resp.get("data") or {}
    # 也读 status
    _, st = api(token, "GET", "/pk/bootstrap/status")
    agents = (st.get("data") or {}).get("agents") or data
    out = {str(k): str(v) for k, v in agents.items() if v}
    if not out:
        raise RuntimeError(f"无 PatentKing 智能体: {resp} / {st}")
    return out


def create_matter(token: str, spec: dict, attach_id: str | None) -> str:
    meta = {
        "demoBatch": "primary-types-20260825",
        "agentCode": spec["agentCode"],
        "scene": spec.get("scene") or "write",
    }
    if attach_id:
        meta["sourceAttachId"] = attach_id
    body = {
        "title": spec["title"],
        "matterType": spec["matterType"],
        "status": "draft",
        "remark": f"批量示例 {spec['matterType']}",
        "metaJson": json.dumps(meta, ensure_ascii=False),
    }
    _, resp = api(token, "POST", "/pk/matter", body)
    if not resp.get("success"):
        raise RuntimeError(f"创建案件失败: {resp}")
    # 回查 id
    _, page = api(token, "GET", "/pk/matter/page?current=1&size=30")
    records = (page.get("data") or {}).get("records") or []
    hit = next((r for r in records if r.get("title") == spec["title"]), None)
    if not hit:
        raise RuntimeError("创建后未找到案件")
    matter_id = str(hit["id"])
    # 绑定默认智能体
    _, bind = api(token, "POST", f"/pk/matter/{matter_id}/bind-default-agent")
    if not bind.get("success"):
        print(f"  WARN bind-default-agent: {bind.get('msg')}")
    _, detail = api(token, "GET", f"/pk/matter/{matter_id}")
    m = detail.get("data") or {}
    agent_id = str(m.get("agentDefinitionId") or "")
    return matter_id, agent_id


def create_session(token: str, agent_id: str, title: str) -> str:
    _, resp = api(token, "POST", "/agent/chat/session", {"agentId": agent_id, "title": title})
    data = resp.get("data") or {}
    sid = str(data.get("id") or "")
    if not sid:
        raise RuntimeError(f"创建会话失败: {resp}")
    return sid


def append_user(token: str, session_id: str, content: str) -> dict:
    _, resp = api(
        token,
        "POST",
        f"/agent/chat/session/{session_id}/message",
        {"role": "user", "content": content},
    )
    return resp.get("data") or {}


def run_agui(
    token: str,
    *,
    agent_code: str,
    agent_id: str,
    session_id: str,
    matter_id: str,
    user_text: str,
    file_ids: list[str] | None = None,
) -> dict:
    """消费 SSE，直到 RUN_FINISHED / RUN_ERROR 或超时。"""
    run_id = f"run_{int(time.time()*1000)}_{os.getpid()}"
    payload = {
        "threadId": session_id,
        "runId": run_id,
        "messages": [{"id": "u1", "role": "user", "content": user_text}],
        "tools": [],
        "context": [],
        "state": {},
        "forwardedProps": {
            "agentId": agent_id,
            "agentCode": agent_code,
            "fileIds": file_ids or [],
            "memoryActive": False,
            "planActive": False,
            "toolProcessActive": True,
            "params": {"matterId": matter_id},
        },
    }
    url = f"{GATEWAY}/api/runtime/agui/run/{agent_code}"
    req = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "X-Apboa-Thread-Id": session_id,
        },
        method="POST",
    )
    started = time.time()
    assistant_chunks: list[str] = []
    tool_names: list[str] = []
    last_event = ""
    error = None
    try:
        with urllib.request.urlopen(req, timeout=RUN_TIMEOUT) as resp:
            buf = ""
            while True:
                if time.time() - started > RUN_TIMEOUT:
                    error = f"timeout>{RUN_TIMEOUT}s"
                    break
                chunk = resp.read(4096)
                if not chunk:
                    break
                buf += chunk.decode("utf-8", "replace")
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    line = line.strip()
                    if not line.startswith("data:"):
                        continue
                    raw = line[5:].strip()
                    if not raw or raw == "[DONE]":
                        continue
                    try:
                        ev = json.loads(raw)
                        if isinstance(ev, str):
                            ev = json.loads(ev)
                    except json.JSONDecodeError:
                        continue
                    if not isinstance(ev, dict):
                        continue
                    et = ev.get("type") or ev.get("event") or ""
                    last_event = str(et)
                    if et in ("TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CHUNK"):
                        delta = ev.get("delta") or ev.get("content") or ""
                        if delta:
                            assistant_chunks.append(str(delta))
                    elif et == "TOOL_CALL_START":
                        name = ev.get("toolCallName") or ev.get("name") or ""
                        if name:
                            tool_names.append(str(name))
                    elif et in ("RUN_FINISHED", "RUN_ERROR"):
                        if et == "RUN_ERROR":
                            error = ev.get("message") or "RUN_ERROR"
                        return {
                            "ok": error is None,
                            "error": error,
                            "seconds": round(time.time() - started, 1),
                            "tools": tool_names,
                            "assistant_len": sum(len(x) for x in assistant_chunks),
                            "last_event": last_event,
                            "preview": "".join(assistant_chunks)[:240],
                        }
    except Exception as e:
        error = str(e)
    return {
        "ok": error is None and last_event == "RUN_FINISHED",
        "error": error,
        "seconds": round(time.time() - started, 1),
        "tools": tool_names,
        "assistant_len": sum(len(x) for x in assistant_chunks),
        "last_event": last_event,
        "preview": "".join(assistant_chunks)[:240],
    }


def bind_and_import(token: str, matter_id: str, session_id: str) -> None:
    try:
        api(token, "POST", f"/pk/matter/{matter_id}/bind-session", {"sessionId": session_id})
    except Exception as e:
        print(f"  WARN bind-session: {e}")
    try:
        api(token, "POST", f"/pk/matter/{matter_id}/import-session", {"sessionId": session_id}, timeout=180)
    except Exception as e:
        print(f"  WARN import-session: {e}")


def seed_fallback_artifact(token: str, matter_id: str, spec: dict, run_info: dict) -> None:
    """若智能体未写出产物，至少登记一份 Markdown 纪要，方便案件页可看。"""
    md = (
        f"# {spec['title']}\n\n"
        f"- 类型：`{spec['matterType']}`\n"
        f"- 智能体：`{spec['agentCode']}`\n"
        f"- 跑批结果：{'成功' if run_info.get('ok') else '未完成'} "
        f"({run_info.get('seconds')}s)\n"
        f"- 工具调用：{', '.join(run_info.get('tools') or []) or '无'}\n"
        f"- 错误：{run_info.get('error') or '无'}\n\n"
        f"## 助手回复摘要\n\n{run_info.get('preview') or '（流式未捕获到文本，请打开关联对话查看）'}\n"
    )
    try:
        api(
            token,
            "POST",
            f"/pk/matter/{matter_id}/artifacts",
            {
                "name": f"{spec['matterType']}-跑批纪要.md",
                "artifactType": "disclosure_md",
                "mime": "text/markdown",
                "metaJson": json.dumps(
                    {"source": "batch-demo", "content": md}, ensure_ascii=False
                ),
            },
        )
    except Exception as e:
        print(f"  WARN seed artifact: {e}")


def main() -> int:
    print(f"CONSOLE={CONSOLE} GATEWAY={GATEWAY}")
    print(f"PDF={PDF} exists={PDF.is_file()}")
    token = login()
    print("login OK")
    agents = bootstrap_agents(token)
    print("agents:", agents)

    attach_id = None
    if PDF.is_file():
        attach_id = upload_pdf(token)
        print(f"uploaded pdf attach_id={attach_id}")

    results = []
    for i, spec in enumerate(TYPE_SPECS, 1):
        print(f"\n==== [{i}/{len(TYPE_SPECS)}] {spec['matterType']} · {spec['title']} ====")
        agent_code = spec["agentCode"]
        if agent_code not in agents:
            print(f"  FAIL missing agent {agent_code}")
            results.append({**spec, "ok": False, "error": "missing agent"})
            continue
        try:
            matter_id, agent_id = create_matter(
                token, spec, attach_id if spec.get("needPdf") else None
            )
            if not agent_id:
                agent_id = agents[agent_code]
            print(f"  matter={matter_id} agent={agent_id}")
            session_id = create_session(token, agent_id, spec["title"][:40])
            print(f"  session={session_id}")

            file_ids = [attach_id] if (spec.get("needPdf") and attach_id) else []
            # 附件前缀与前端一致，便于模型感知
            user_text = spec["prompt"]
            if file_ids:
                user_text = (
                    json.dumps({"files": [{"id": attach_id, "name": "main_zh.pdf"}]}, ensure_ascii=False)
                    + "@==##::::##==@"
                    + user_text
                )
            append_user(token, session_id, user_text)
            run_info = run_agui(
                token,
                agent_code=agent_code,
                agent_id=agent_id,
                session_id=session_id,
                matter_id=matter_id,
                user_text=user_text,
                file_ids=file_ids,
            )
            print(
                f"  run ok={run_info['ok']} {run_info['seconds']}s "
                f"tools={run_info['tools']} err={run_info.get('error')} last={run_info.get('last_event')}"
            )
            bind_and_import(token, matter_id, session_id)
            # 列产物
            _, arts = api(token, "GET", f"/pk/matter/{matter_id}/artifacts")
            art_list = arts.get("data") or []
            print(f"  artifacts={len(art_list)}")
            if not art_list:
                seed_fallback_artifact(token, matter_id, spec, run_info)
                _, arts = api(token, "GET", f"/pk/matter/{matter_id}/artifacts")
                art_list = arts.get("data") or []
                print(f"  artifacts(after seed)={len(art_list)}")
            results.append(
                {
                    "matterType": spec["matterType"],
                    "title": spec["title"],
                    "matterId": matter_id,
                    "sessionId": session_id,
                    "agentCode": agent_code,
                    "ok": run_info["ok"],
                    "seconds": run_info["seconds"],
                    "tools": run_info["tools"],
                    "error": run_info.get("error"),
                    "artifactCount": len(art_list),
                    "url": f"{GATEWAY}/#/patent-matters/{matter_id}",
                }
            )
        except Exception as e:
            print(f"  FAIL {e}")
            results.append({**spec, "ok": False, "error": str(e)})

    out_path = ROOT / "scripts" / "pk-all-types-results.json"
    out_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n======== 汇总 ========")
    for r in results:
        print(
            f"- {r.get('matterType')}: matter={r.get('matterId')} "
            f"ok={r.get('ok')} arts={r.get('artifactCount')} {r.get('url') or r.get('error')}"
        )
    print(f"\n结果已写: {out_path}")
    ok_n = sum(1 for r in results if r.get("ok"))
    return 0 if ok_n == len(TYPE_SPECS) else 1


if __name__ == "__main__":
    sys.exit(main())
