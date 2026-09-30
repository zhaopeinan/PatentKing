"""Export Markdown disclosure to Word with machine-rendered mermaid PNGs."""

from __future__ import annotations

import base64
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path


def _tools_dir() -> Path:
    env = os.environ.get("PK_SKILL_TOOLS", "").strip()
    if env:
        return Path(env)
    here = Path(__file__).resolve()
    candidate = here.parents[3] / "patent-skills" / "patent-mining-disclosure-skill" / "tools"
    if (candidate / "mermaid_render.py").is_file():
        return candidate
    return here.parent.parent / "vendor"


def _export_root() -> Path:
    root = Path(os.environ.get("PK_EXPORT_DIR", "/tmp/pk-exports"))
    root.mkdir(parents=True, exist_ok=True)
    return root


def _safe_stem(title: str) -> str:
    text = (title or "disclosure").strip() or "disclosure"
    text = re.sub(r"[\\/:*?\"<>|\s]+", "_", text)
    return text[:80] or "disclosure"


def get_export_file(export_id: str) -> Path | None:
    if not export_id or not re.fullmatch(r"[a-fA-F0-9\-]{8,64}", export_id):
        return None
    path = _export_root() / f"{export_id}.docx"
    return path if path.is_file() else None


def _normalize_diagram_mode(mode: str) -> str:
    m = (mode or "png").strip().lower()
    if m in {"auto", "tokenlab", "image-api"}:
        return "auto"
    return "png"


_MERMAID_BLOCK_RE = re.compile(
    r"^```mermaid[^\n]*\n(.*?)```(?:[ \t]*\n(?:[ \t]*\n)*<!--\s*!\[图示\s*(\d+)\]\(([^)]+)\)\s*-->)?",
    re.MULTILINE | re.DOTALL,
)


def _figure_preview(body: str) -> str:
    for line in (body or "").splitlines():
        s = line.strip()
        if not s or s.startswith("flowchart") or s.startswith("graph") or s.startswith("sequenceDiagram"):
            continue
        return s[:80]
    return (body or "").strip().splitlines()[0][:80] if (body or "").strip() else "（空图）"


def _extract_figure_status(rendered_md: str, *, mode: str, log: str) -> list[dict]:
    """从定稿 MD 抽出每张图：成功=围栏后有图示注释；失败=仍仅源码。"""
    fallback_idx = {
        int(x)
        for x in re.findall(r"第\s*(\d+)\s*个图示已用 mmdc 回退", log or "")
    }
    tokenlab_fail_idx = {
        int(x)
        for x in re.findall(r"第\s*(\d+)\s*个图示 Tokenlab 失败", log or "")
    }
    figures: list[dict] = []
    for i, m in enumerate(_MERMAID_BLOCK_RE.finditer(rendered_md or ""), start=1):
        body = m.group(1) or ""
        ok = bool(m.group(2) and m.group(3))
        engine = "source"
        if ok:
            if mode == "auto":
                if i in fallback_idx or i in tokenlab_fail_idx:
                    engine = "mmdc_fallback"
                else:
                    engine = "tokenlab"
            else:
                engine = "mmdc"
        figures.append(
            {
                "index": i,
                "preview": _figure_preview(body),
                "status": "ok" if ok else "failed",
                "engine": engine,
                "source": body.strip()[:2000],
            }
        )
    return figures


def export_markdown_to_docx(
    markdown: str,
    *,
    title: str = "disclosure",
    diagram_mode: str = "png",
    include_pdf: bool = False,
    return_base64: bool = False,
    tokenlab: dict | None = None,
) -> dict:
    """
    Run skill mermaid_render.py (+ md_to_docx).

    Persists docx under PK_EXPORT_DIR and returns export_id for download.
    """
    md = (markdown or "").strip()
    if not md:
        return {"ok": False, "message": "markdown 为空", "export_id": None}

    tools = _tools_dir()
    script = tools / "mermaid_render.py"
    if not script.is_file():
        return {
            "ok": False,
            "message": f"未找到 mermaid_render.py（PK_SKILL_TOOLS={tools}）",
            "export_id": None,
        }

    stem = _safe_stem(title)
    mode = _normalize_diagram_mode(diagram_mode)

    if mode == "png" and not shutil.which("mmdc") and not shutil.which("npx"):
        return {
            "ok": False,
            "message": "容器内未安装 mmdc/npx，无法 PNG 机器渲染 mermaid",
            "export_id": None,
        }

    if mode == "auto":
        tl = tokenlab or {}
        api_key = str(tl.get("api_key") or "").strip()
        if not api_key:
            return {
                "ok": False,
                "message": "diagram_mode=auto 需要 Tokenlab 配置（api_key）。请在系统设置 → PatentKing 生图中填写。",
                "export_id": None,
            }

    work = Path(tempfile.mkdtemp(prefix="pk-export-"))
    try:
        draft = work / "draft.md"
        out_md = work / f"{stem}.md"
        draft.write_text(md + ("\n" if not md.endswith("\n") else ""), encoding="utf-8")

        cmd = [sys.executable, str(script), "-i", str(draft), "-o", str(out_md)]
        if mode == "auto":
            cmd.extend(["--diagram-mode", "auto"])
        if include_pdf:
            cmd.append("--pdf")

        env = os.environ.copy()
        env.setdefault("PUPPETEER_EXECUTABLE_PATH", "/usr/bin/chromium")
        env.setdefault("PUPPETEER_SKIP_DOWNLOAD", "true")
        if mode == "auto" and tokenlab:
            env["TOKENLAB_API_KEY"] = str(tokenlab.get("api_key") or "")
            if tokenlab.get("base_url"):
                env["TOKENLAB_BASE_URL"] = str(tokenlab["base_url"])
            if tokenlab.get("fallback_base_url"):
                env["TOKENLAB_FALLBACK_BASE_URL"] = str(tokenlab["fallback_base_url"])
            if tokenlab.get("model"):
                env["TOKENLAB_MODEL"] = str(tokenlab["model"])
            if tokenlab.get("size"):
                env["TOKENLAB_SIZE"] = str(tokenlab["size"])
            if tokenlab.get("quality"):
                env["TOKENLAB_QUALITY"] = str(tokenlab["quality"])
            if tokenlab.get("network"):
                env["TOKENLAB_NETWORK"] = str(tokenlab["network"])
            if tokenlab.get("proxy"):
                env["TOKENLAB_PROXY"] = str(tokenlab["proxy"])

        proc = subprocess.run(
            cmd,
            cwd=str(tools),
            capture_output=True,
            text=True,
            timeout=900 if mode == "auto" else 600,
            env=env,
        )
        log = "\n".join(x for x in ((proc.stdout or "").strip(), (proc.stderr or "").strip()) if x)[-4000:]

        if not out_md.is_file():
            return {
                "ok": False,
                "message": f"渲染失败（exit={proc.returncode}）：{log or '无输出'}",
                "export_id": None,
                "log": log,
            }

        rendered_md = out_md.read_text(encoding="utf-8")
        fig_dir = out_md.parent / "mermaid_figures"
        figure_count = len(list(fig_dir.glob("*.png"))) if fig_dir.is_dir() else 0
        comment_figs = len(re.findall(r"<!--\s*!\[图示", rendered_md))
        if comment_figs:
            figure_count = max(figure_count, comment_figs)
        # 以定稿 MD 为准：成功块=围栏+图示注释；无注释的 mermaid 围栏会进 Word 源码
        figures = _extract_figure_status(rendered_md, mode=mode, log=log)
        figure_count = max(figure_count, sum(1 for f in figures if f.get("status") == "ok"))
        failed_count = sum(1 for f in figures if f.get("status") != "ok")
        if not figures:
            mermaid_blocks = len(re.findall(r"^```mermaid\b", rendered_md, flags=re.M))
            failed_count = max(0, mermaid_blocks - comment_figs)

        docx_path = out_md.with_suffix(".docx")
        if not docx_path.is_file():
            return {
                "ok": False,
                "message": f"Markdown 已写出但 Word 未生成：{log or '见 mermaid_render 日志'}",
                "markdown": rendered_md,
                "figure_count": figure_count,
                "failed_count": failed_count,
                "export_id": None,
                "log": log,
            }

        export_id = str(uuid.uuid4())
        dest = _export_root() / f"{export_id}.docx"
        shutil.copy2(docx_path, dest)
        meta = _export_root() / f"{export_id}.meta"
        meta.write_text(
            f"filename={stem}.docx\ncreated={int(time.time())}\nfigures={figure_count}\n",
            encoding="utf-8",
        )

        if mode == "png":
            msg = "已机器渲染 mermaid 并生成 Word，禁止再让用户手工渲染。"
        elif failed_count:
            msg = f"已通过 Tokenlab/mmdc 生成图示并写入 Word；{failed_count} 处最终仍失败已保留源码。"
        else:
            msg = "已通过 Tokenlab 自动生成图示并写入 Word（失败块已自动回退 mmdc）。"

        out: dict = {
            "ok": True,
            "message": msg,
            "export_id": export_id,
            "filename": f"{stem}.docx",
            "figure_count": figure_count,
            "failed_count": failed_count,
            "figures": figures,
            "diagram_mode": mode,
            "bytes": dest.stat().st_size,
            "log": log[-1500:] if log else "",
        }
        if return_base64:
            out["docx_base64"] = base64.b64encode(dest.read_bytes()).decode("ascii")
        return out
    except subprocess.TimeoutExpired:
        return {"ok": False, "message": "导出超时（600s）", "export_id": None}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "message": f"导出异常：{e}", "export_id": None}
    finally:
        shutil.rmtree(work, ignore_errors=True)
