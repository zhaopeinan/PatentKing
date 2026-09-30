"""Prior-art search for PatentKing sidecar — 国知局公布公告站 epub.cnipa.gov.cn。

使用 Playwright 过站点 WAF，解析公开号、标题、摘要与详情链接。
国知局首页检索框对空格多为 AND：多词一次提交极易 0 条。
本模块会对空格拆词，逐词检索后按公开号去重合并。

鲁棒性：
- 多 base URL（https/http）+ DNS 预检 + 指数退避重试（见 cnipa_epub_crawler）
- 国知局仍失败时自动降级 Google Patents（source=google_patents）
- 全部失败才 degrade=websearch
"""

from __future__ import annotations

import logging
import os
import sys
import threading
from pathlib import Path
from typing import Any

# vendor：Dockerfile COPY 至 vendor/crawl + vendor/shared
_VENDOR = Path(__file__).resolve().parents[1] / "vendor"
for sub in (_VENDOR / "crawl", _VENDOR / "shared"):
    if sub.is_dir():
        s = str(sub)
        if s not in sys.path:
            sys.path.insert(0, s)

from .google_patents_search import search_google_patents

log = logging.getLogger("pk.search")

MAX_HITS = 20
MAX_TERMS = 4
DEFAULT_PATENT_TYPE = "invention"
CNIPA_HOME = "https://epub.cnipa.gov.cn/"
# 国知局 Playwright 串行，避免并发多开 Chromium 导致超时
_SEARCH_LOCK = threading.Lock()


def search_prior_art(
    query: str,
    limit: int = 8,
    *,
    patent_type: str | None = None,
) -> dict[str, Any]:
    q = (query or "").strip()
    if not q:
        return _fail("query 为空", query=q, degrade="websearch")

    lim = max(1, min(int(limit or 8), MAX_HITS))
    ptype = (patent_type or os.environ.get("PK_CNIPA_PATENT_TYPE") or DEFAULT_PATENT_TYPE).strip()
    os.environ.setdefault("EPUB_WAF_MAX_WAIT_SEC", "180")

    terms = _split_terms(q)
    cnipa_errors: list[str] = []
    strategy: str | None = None
    hits: list[dict[str, Any]] | None = None

    try:
        with _SEARCH_LOCK:
            hits, strategy = _cnipa_search_terms(terms, lim, ptype)
    except ImportError as e:
        cnipa_errors.append(str(e))
        return _try_google_or_fail(
            q,
            terms,
            lim,
            ptype,
            cnipa_errors,
            error_kind="import_error",
            message_prefix=f"国知局查新依赖未安装（{e}）。",
        )
    except Exception as e:
        kind = _classify_error(e)
        cnipa_errors.append(f"cnipa[{kind}]: {e}")
        log.warning("CNIPA search failed (%s): %s", kind, e)
        return _try_google_or_fail(
            q,
            terms,
            lim,
            ptype,
            cnipa_errors,
            error_kind=kind,
            message_prefix=f"国知局查新失败（{kind}）：{e}。",
            strategy=None,
        )

    if hits:
        msg = f"国知局公布公告站命中 {len(hits)} 条中国专利"
        if len(terms) > 1:
            msg += f"（已按空格拆成 {len(terms)} 词分别检索后合并）"
        return {
            "ok": True,
            "source": "cnipa_epub",
            "degrade": None,
            "message": msg,
            "query": q,
            "terms": terms,
            "strategy": strategy,
            "patent_type": ptype,
            "cnipa_home": CNIPA_HOME,
            "hits": hits[:lim],
            "errors": [],
        }

    # 国知局成功访问但 0 条：再试 Google Patents 宽召回
    gp_hits, gp_err = _google_patents_safe(q, lim)
    if gp_hits:
        return {
            "ok": True,
            "source": "google_patents",
            "degrade": None,
            "fallback_from": "cnipa_epub",
            "fallback_reason": "zero_hits",
            "message": (
                f"国知局公布公告站检索「{q}」无命中，已自动改用 Google Patents 命中 {len(gp_hits)} 条。"
                "请在资产清单注明：国知局站未命中，结果来自 Google Patents 公开索引。"
            ),
            "query": q,
            "terms": terms,
            "strategy": strategy,
            "patent_type": ptype,
            "cnipa_home": CNIPA_HOME,
            "google_patents_url": _google_patents_url(q),
            "hits": gp_hits[:lim],
            "errors": [gp_err] if gp_err else [],
        }

    return {
        "ok": False,
        "source": "cnipa_epub",
        "degrade": "websearch",
        "error_kind": "zero_hits",
        "message": (
            f"国知局公布公告站与 Google Patents 均未检索到「{q}」相关中国专利"
            + (f"（已拆词：{' / '.join(terms)}）" if len(terms) > 1 else "")
            + "。请换更宽关键词重试，或降级网页搜索并在资产清单标明「未完成公开专利检索」。"
        ),
        "query": q,
        "terms": terms,
        "strategy": strategy,
        "patent_type": ptype,
        "cnipa_home": CNIPA_HOME,
        "google_patents_url": _google_patents_url(q),
        "hits": [],
        "errors": [e for e in [gp_err] if e],
    }


def _try_google_or_fail(
    q: str,
    terms: list[str],
    lim: int,
    ptype: str,
    cnipa_errors: list[str],
    *,
    error_kind: str,
    message_prefix: str,
    strategy: str | None = None,
) -> dict[str, Any]:
    gp_hits, gp_err = _google_patents_safe(q, lim)
    if gp_err:
        cnipa_errors.append(gp_err)
    if gp_hits:
        return {
            "ok": True,
            "source": "google_patents",
            "degrade": None,
            "fallback_from": "cnipa_epub",
            "fallback_reason": error_kind,
            "message": (
                f"{message_prefix}已自动改用 Google Patents，命中 {len(gp_hits)} 条。"
                "请在资产清单注明：国知局站暂不可达，结果来自 Google Patents 公开索引。"
            ),
            "query": q,
            "terms": terms,
            "strategy": strategy,
            "patent_type": ptype,
            "cnipa_home": CNIPA_HOME,
            "google_patents_url": _google_patents_url(q),
            "hits": gp_hits[:lim],
            "errors": cnipa_errors,
        }
    return _fail(
        f"{message_prefix}Google Patents 降级也未命中。"
        "请降级网页搜索，并在资产清单标明「未完成公开专利检索」。",
        query=q,
        degrade="websearch",
        errors=cnipa_errors,
        error_kind=error_kind,
        terms=terms,
        strategy=strategy,
        patent_type=ptype,
    )


def _google_patents_safe(query: str, limit: int) -> tuple[list[dict[str, Any]], str | None]:
    try:
        hits = search_google_patents(query, limit)
        return hits, None
    except Exception as e:
        log.warning("Google Patents fallback failed: %s", e)
        return [], f"google_patents: {e}"


def _google_patents_url(query: str) -> str:
    import urllib.parse

    return f"https://patents.google.com/?q={urllib.parse.quote(query)}&country=CN"


def _classify_error(exc: BaseException) -> str:
    msg = str(exc).lower()
    if "err_name_not_resolved" in msg or "name or service not known" in msg:
        return "dns"
    if "timeout" in msg or "timed out" in msg:
        return "timeout"
    if "#searchstr" in msg or "waf" in msg:
        return "waf"
    if "connection" in msg or "net::err_" in msg:
        return "network"
    return "unknown"


def _split_terms(query: str) -> list[str]:
    """按空白拆词；过长列表截断，避免 Playwright 耗时爆炸。"""
    terms: list[str] = []
    for part in query.split():
        p = part.strip()
        if p and p not in terms:
            terms.append(p)
    if not terms:
        return [query]
    if len(terms) > MAX_TERMS:
        log.warning("query has %d terms, keep first %d", len(terms), MAX_TERMS)
        terms = terms[:MAX_TERMS]
    return terms


def _cnipa_search_terms(
    terms: list[str],
    limit: int,
    patent_type: str,
) -> tuple[list[dict[str, Any]], str]:
    from cnipa_epub_crawler import search_epub_keywords
    from cnipa_epub_parse import hits_to_jsonable
    from patent_type import normalize_patent_type

    ptype = normalize_patent_type(patent_type, default=DEFAULT_PATENT_TYPE)

    if len(terms) == 1:
        rows = search_epub_keywords(terms, patent_type=ptype)
        raw = hits_to_jsonable(rows[0][1] if rows else [])
        return _normalize_hits(raw, limit), "single"

    rows = search_epub_keywords(terms, patent_type=ptype)
    merged: list[dict[str, Any]] = []
    seen: set[str] = set()
    for _html, batch in rows:
        for h in hits_to_jsonable(batch):
            key = str(h.get("pub_number") or h.get("link") or h.get("title") or "").strip()
            if not key or key in seen:
                continue
            seen.add(key)
            merged.append(h)
            if len(merged) >= limit:
                break
        if len(merged) >= limit:
            break
    return _normalize_hits(merged, limit), "split_or_merge"


def _normalize_hits(hits: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for h in hits[:limit]:
        pub = str(h.get("pub_number") or "").strip()
        link = str(h.get("link") or "").strip()
        if not link and pub:
            link = f"https://epub.cnipa.gov.cn/patent/{pub}"
        out.append(
            {
                "kind": "patent",
                "pub_number": pub,
                "title": str(h.get("title") or "").strip(),
                "url": link,
                "link": link,
                "abstract": str(h.get("abstract") or "").strip(),
                "snippet": str(h.get("abstract") or "").strip()[:240],
            }
        )
    return out


def _fail(
    message: str,
    *,
    query: str = "",
    degrade: str,
    errors: list[str] | None = None,
    error_kind: str = "unknown",
    terms: list[str] | None = None,
    strategy: str | None = None,
    patent_type: str = DEFAULT_PATENT_TYPE,
) -> dict[str, Any]:
    return {
        "ok": False,
        "source": "cnipa_epub",
        "degrade": degrade,
        "error_kind": error_kind,
        "message": message,
        "query": query,
        "terms": terms or ([query] if query else []),
        "strategy": strategy,
        "patent_type": patent_type,
        "cnipa_home": CNIPA_HOME,
        "google_patents_url": _google_patents_url(query) if query else "",
        "hits": [],
        "errors": errors or [],
    }
