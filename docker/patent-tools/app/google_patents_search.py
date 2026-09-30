"""Google Patents Playwright fallback — 国知局 epub 不可达时自动降级检索。"""

from __future__ import annotations

import os
import sys
import urllib.parse
from pathlib import Path
from typing import Any

_VENDOR = Path(__file__).resolve().parents[1] / "vendor"
_shared = _VENDOR / "shared"
if _shared.is_dir() and str(_shared) not in sys.path:
    sys.path.insert(0, str(_shared))

from playwright.sync_api import sync_playwright

from browser import launch_chromium

_EXTRACT_JS = """() => {
  const out = [];
  const seen = new Set();
  const pubFromText = (text) => {
    const m = (text || '').match(/\\b([A-Z]{2}\\d{6,}[A-Z]?\\d?)\\b/i);
    return m ? m[1].toUpperCase() : '';
  };
  const push = (pub, title, link, abstract) => {
    pub = (pub || '').trim().toUpperCase();
    if (!pub || seen.has(pub)) return;
    seen.add(pub);
    const url = link || `https://patents.google.com/patent/${pub}/zh`;
    out.push({
      pub_number: pub,
      title: (title || '').trim(),
      link: url,
      abstract: (abstract || '').trim().slice(0, 600),
    });
  };
  document.querySelectorAll('search-result-item').forEach(el => {
    const mod = el.querySelector('[data-result]');
    const dataResult = mod ? (mod.getAttribute('data-result') || '') : '';
    let pub = '';
    const dm = dataResult.match(/patent\\/([^/?#]+)/i);
    if (dm) pub = dm[1].toUpperCase();
    if (!pub) {
      const pdf = el.querySelector('a[href*="patentimages"]');
      if (pdf) pub = pubFromText(pdf.textContent || pdf.getAttribute('href') || '');
    }
    if (!pub) pub = pubFromText(el.textContent || '');
    const titleEl = el.querySelector('h3, raw-html span, #htmlContent');
    const title = (titleEl ? titleEl.textContent : '') || '';
    const absEl = el.querySelector('.abstract, [class*="abstract"]');
    push(pub, title, pub ? `https://patents.google.com/patent/${pub}/zh` : '', absEl ? absEl.textContent : '');
  });
  return out;
}"""


def search_google_patents(
    query: str,
    limit: int = 8,
    *,
    country: str = "CN",
) -> list[dict[str, Any]]:
    q = (query or "").strip()
    if not q:
        return []

    lim = max(1, min(int(limit or 8), 20))
    params = urllib.parse.urlencode({"q": q, "country": country})
    url = f"https://patents.google.com/?{params}"
    headless = os.environ.get("PLAYWRIGHT_HEADED", "").strip().lower() not in (
        "1",
        "true",
        "yes",
    )

    with sync_playwright() as p:
        browser, _ = launch_chromium(p, headless=headless)
        context = browser.new_context(
            locale="zh-CN",
            viewport={"width": 1280, "height": 900},
        )
        page = context.new_page()
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=90_000)
            page.wait_for_timeout(2500)
            try:
                page.wait_for_selector("search-result-item", timeout=30_000)
            except Exception:
                pass
            raw = page.evaluate(_EXTRACT_JS)
            hits: list[dict[str, Any]] = []
            for h in raw[:lim]:
                pub = str(h.get("pub_number") or "").strip()
                link = str(h.get("link") or "").strip()
                if not link and pub:
                    link = f"https://patents.google.com/patent/{pub}/zh"
                abstract = str(h.get("abstract") or "").strip()
                hits.append(
                    {
                        "kind": "patent",
                        "pub_number": pub,
                        "title": str(h.get("title") or "").strip(),
                        "url": link,
                        "link": link,
                        "abstract": abstract,
                        "snippet": abstract[:240],
                    }
                )
            return hits
        finally:
            context.close()
            browser.close()
