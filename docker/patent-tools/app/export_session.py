"""分步导出：按图示并发渲染（Tokenlab / mmdc）后组装 Word。"""

from __future__ import annotations

import json
import os
import re
import shutil
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any

from .export_docx import _extract_figure_status, _normalize_diagram_mode, _safe_stem, _tools_dir

_MERMAID_BLOCK_RE = re.compile(
    r"^```mermaid[^\n]*\n(.*?)```",
    re.MULTILINE | re.DOTALL,
)
_PK_SIZE_HINT_RE = re.compile(
    r"<!--\s*pk-diagram-size:\s*(\d+x\d+)\s*-->",
    re.IGNORECASE,
)

_SESSION_ROOT = Path(os.environ.get("PK_EXPORT_DIR", "/tmp/pk-exports")) / "sessions"
_LOCKS: dict[tuple[str, int], threading.Lock] = {}
_SESSION_META_LOCK = threading.Lock()


def _session_dir(session_id: str) -> Path:
    if not re.fullmatch(r"[a-fA-F0-9\-]{8,64}", session_id or ""):
        raise ValueError("无效的 session_id")
    return _SESSION_ROOT / session_id


def _load_mermaid_render():
    tools = _tools_dir()
    crawl = str(tools)
    if crawl not in sys.path:
        sys.path.insert(0, crawl)
    import mermaid_render  # noqa: WPS433

    return mermaid_render


def _figure_preview(body: str) -> str:
    for line in (body or "").splitlines():
        s = line.strip()
        if not s or s.startswith("flowchart") or s.startswith("graph") or s.startswith("sequenceDiagram"):
            continue
        return s[:80]
    lines = (body or "").strip().splitlines()
    return lines[0][:80] if lines else "（空图）"


def list_mermaid_blocks(md: str) -> list[dict[str, Any]]:
    blocks: list[dict[str, Any]] = []
    for i, m in enumerate(_MERMAID_BLOCK_RE.finditer(md or ""), start=1):
        body = (m.group(1) or "").strip()
        blocks.append({"index": i, "preview": _figure_preview(body), "source": body[:2000]})
    return blocks


def _size_hint_before(md: str, block_start: int) -> str | None:
    prefix = (md or "")[:block_start]
    hints = _PK_SIZE_HINT_RE.findall(prefix)
    return hints[-1].lower() if hints else None


def _block_match(md: str, index: int) -> re.Match[str] | None:
    for i, m in enumerate(_MERMAID_BLOCK_RE.finditer(md or ""), start=1):
        if i == index:
            return m
    return None


def _read_meta(session_dir: Path) -> dict[str, Any]:
    meta_path = session_dir / "meta.json"
    if not meta_path.is_file():
        return {}
    try:
        return json.loads(meta_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def _write_meta(session_dir: Path, meta: dict[str, Any]) -> None:
    (session_dir / "meta.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def _figure_lock(session_id: str, index: int) -> threading.Lock:
    key = (session_id, index)
    with _SESSION_META_LOCK:
        if key not in _LOCKS:
            _LOCKS[key] = threading.Lock()
        return _LOCKS[key]


def create_export_session(markdown: str, *, diagram_mode: str = "png") -> dict[str, Any]:
    md = (markdown or "").strip()
    if not md:
        return {"ok": False, "message": "markdown 为空"}

    mode = _normalize_diagram_mode(diagram_mode)
    session_id = str(uuid.uuid4())
    session_dir = _SESSION_ROOT / session_id
    session_dir.mkdir(parents=True, exist_ok=True)
    (session_dir / "draft.md").write_text(md + ("\n" if not md.endswith("\n") else ""), encoding="utf-8")
    (session_dir / "mermaid_figures").mkdir(exist_ok=True)

    figures = list_mermaid_blocks(md)
    meta = {
        "session_id": session_id,
        "diagram_mode": mode,
        "created_at": int(time.time()),
        "figures": {str(f["index"]): {"status": "pending", "engine": "", "message": ""} for f in figures},
    }
    _write_meta(session_dir, meta)

    return {
        "ok": True,
        "session_id": session_id,
        "diagram_mode": mode,
        "figures": figures,
        "message": f"已创建导出会话，共 {len(figures)} 张图示待渲染",
    }


def render_session_figure(
    session_id: str,
    index: int,
    *,
    diagram_mode: str | None = None,
    tokenlab: dict | None = None,
    force: bool = False,
) -> dict[str, Any]:
    if index < 1:
        return {"ok": False, "message": "index 须 >= 1", "index": index}

    session_dir = _session_dir(session_id)
    draft_path = session_dir / "draft.md"
    if not draft_path.is_file():
        return {"ok": False, "message": "导出会话不存在或已过期", "index": index}

    with _figure_lock(session_id, index):
        meta = _read_meta(session_dir)
        fig_key = str(index)
        prev = (meta.get("figures") or {}).get(fig_key) or {}
        if prev.get("status") == "ok" and not force:
            return {
                "ok": True,
                "index": index,
                "status": "ok",
                "engine": prev.get("engine") or "cached",
                "message": "已渲染（缓存）",
            }

        mode = _normalize_diagram_mode(diagram_mode or meta.get("diagram_mode") or "png")
        md = draft_path.read_text(encoding="utf-8")
        match = _block_match(md, index)
        if not match:
            return {"ok": False, "message": f"未找到第 {index} 个 mermaid 图示", "index": index}

        mermaid_body = match.group(1) or ""
        size_hint = _size_hint_before(md, match.start())
        assets_dir = session_dir / "mermaid_figures"
        png_path = assets_dir / f"fig_{index:03d}.png"

        mr = _load_mermaid_render()
        sanitized = mr.sanitize_mermaid_source(mermaid_body)
        engine = "mmdc"
        err_msg = ""

        try:
            if mode == "auto":
                tl = tokenlab or {}
                api_key = str(tl.get("api_key") or "").strip()
                if not api_key:
                    return {
                        "ok": False,
                        "message": "diagram_mode=auto 需要 Tokenlab api_key",
                        "index": index,
                        "status": "failed",
                    }
                block_size = mr._resolve_image_size(  # noqa: SLF001
                    str(tl.get("size") or os.environ.get("TOKENLAB_SIZE") or "1536x1024"),
                    sanitized,
                    size_hint=size_hint,
                )
                prompt = mr._patent_diagram_prompt_text(  # noqa: SLF001
                    sanitized,
                    aspect_hint=mr._aspect_hint_for_size(block_size),  # noqa: SLF001
                )
                base_url = str(tl.get("base_url") or os.environ.get("TOKENLAB_BASE_URL") or "")
                try:
                    mr._generate_one_image_api(  # noqa: SLF001
                        prompt,
                        png_path,
                        api_key=api_key,
                        base_url=base_url,
                        model=str(tl.get("model") or os.environ.get("TOKENLAB_MODEL") or "gpt-image-2"),
                        size=block_size,
                        quality=tl.get("quality"),
                        output_format="png",
                        timeout=int(tl.get("timeout") or 300),
                    )
                    engine = "tokenlab"
                except Exception as e:
                    err_msg = str(e)
                    mmdc_base, use_shell = mr._find_mmdc_invocation()  # noqa: SLF001
                    mr._render_one_mermaid(  # noqa: SLF001
                        sanitized,
                        png_path,
                        mmdc_base,
                        use_shell=use_shell,
                        scale=2.0,
                        width=1400,
                        height=1050,
                    )
                    engine = "mmdc_fallback"
            else:
                mmdc_base, use_shell = mr._find_mmdc_invocation()  # noqa: SLF001
                mr._render_one_mermaid(  # noqa: SLF001
                    sanitized,
                    png_path,
                    mmdc_base,
                    use_shell=use_shell,
                    scale=2.0,
                    width=1400,
                    height=1050,
                )
                engine = "mmdc"

            if not png_path.is_file():
                raise RuntimeError("渲染完成但 PNG 不存在")

            figures = meta.setdefault("figures", {})
            figures[fig_key] = {
                "status": "ok",
                "engine": engine,
                "message": "",
                "preview": _figure_preview(mermaid_body),
            }
            _write_meta(session_dir, meta)
            return {
                "ok": True,
                "index": index,
                "status": "ok",
                "engine": engine,
                "message": "渲染成功",
                "preview": _figure_preview(mermaid_body),
            }
        except Exception as e:
            combined = f"{err_msg}; {e}" if err_msg else str(e)
            figures = meta.setdefault("figures", {})
            figures[fig_key] = {
                "status": "failed",
                "engine": "source",
                "message": combined[:500],
                "preview": _figure_preview(mermaid_body),
            }
            _write_meta(session_dir, meta)
            return {
                "ok": False,
                "index": index,
                "status": "failed",
                "engine": "source",
                "message": combined[:500],
                "preview": _figure_preview(mermaid_body),
            }


def _assemble_markdown(session_dir: Path) -> str:
    md = (session_dir / "draft.md").read_text(encoding="utf-8")
    meta = _read_meta(session_dir)
    figure_states: dict[str, Any] = meta.get("figures") or {}
    assets_rel = "mermaid_figures"

    lines = md.splitlines(keepends=True)
    result: list[str] = []
    idx = 0
    block_idx = 0
    mmd_start = re.compile(r"^```mermaid\s*$", re.IGNORECASE)
    mmd_end = re.compile(r"^```\s*$")
    ok_count = 0

    while idx < len(lines):
        line = lines[idx]
        if mmd_start.match(line):
            block_idx += 1
            fence_open = line
            idx += 1
            body: list[str] = []
            while idx < len(lines) and not mmd_end.match(lines[idx]):
                body.append(lines[idx])
                idx += 1
            closing = lines[idx] if idx < len(lines) else "```\n"
            if idx < len(lines):
                idx += 1

            result.append(fence_open)
            result.extend(body)
            if not closing.endswith("\n"):
                closing += "\n"
            result.append(closing)

            state = figure_states.get(str(block_idx)) or {}
            png = session_dir / assets_rel / f"fig_{block_idx:03d}.png"
            if state.get("status") == "ok" and png.is_file():
                ok_count += 1
                rel = f"{assets_rel}/fig_{block_idx:03d}.png"
                result.append(f"<!-- ![图示 {ok_count}]({rel}) -->\n")
            continue

        result.append(line)
        idx += 1

    return "".join(result)


def finalize_export_session(
    session_id: str,
    *,
    title: str = "disclosure",
    diagram_mode: str | None = None,
) -> dict[str, Any]:
    from .export_docx import _export_root  # noqa: WPS433

    session_dir = _session_dir(session_id)
    if not (session_dir / "draft.md").is_file():
        return {"ok": False, "message": "导出会话不存在或已过期", "export_id": None}

    meta = _read_meta(session_dir)
    mode = _normalize_diagram_mode(diagram_mode or meta.get("diagram_mode") or "png")
    stem = _safe_stem(title)
    rendered_md = _assemble_markdown(session_dir)
    out_md = session_dir / f"{stem}.md"
    out_md.write_text(rendered_md, encoding="utf-8")

    mr = _load_mermaid_render()
    docx_path = out_md.with_suffix(".docx")
    if not mr.try_write_docx(out_md, docx_path):
        return {
            "ok": False,
            "message": "组装 Markdown 成功但 Word 生成失败",
            "export_id": None,
        }

    figures = _extract_figure_status(rendered_md, mode=mode, log="")
    # 用 session meta 补充 engine
    for f in figures:
        st = (meta.get("figures") or {}).get(str(f.get("index"))) or {}
        if st.get("engine"):
            f["engine"] = st["engine"]
        if st.get("message") and f.get("status") != "ok":
            f["message"] = st.get("message")

    figure_count = sum(1 for f in figures if f.get("status") == "ok")
    failed_count = sum(1 for f in figures if f.get("status") != "ok")

    export_id = str(uuid.uuid4())
    dest = _export_root() / f"{export_id}.docx"
    shutil.copy2(docx_path, dest)
    meta_file = _export_root() / f"{export_id}.meta"
    meta_file.write_text(
        f"filename={stem}.docx\ncreated={int(time.time())}\nfigures={figure_count}\n",
        encoding="utf-8",
    )

    if mode == "png":
        msg = "已机器渲染 mermaid 并生成 Word。"
    elif failed_count:
        msg = f"已通过 Tokenlab/mmdc 生成图示并写入 Word；{failed_count} 处仍失败已保留源码。"
    else:
        msg = "已通过 Tokenlab 自动生成图示并写入 Word。"

    return {
        "ok": True,
        "message": msg,
        "export_id": export_id,
        "filename": f"{stem}.docx",
        "figure_count": figure_count,
        "failed_count": failed_count,
        "figures": figures,
        "diagram_mode": mode,
        "bytes": dest.stat().st_size,
        "session_id": session_id,
    }
