#!/usr/bin/env python
"""
Playwright 浏览器启动（patent-tools Docker 版）。

优先使用 CHROME_PATH / PATENT_BROWSER_EXECUTABLE（Docker 内 /usr/bin/chromium），
其次系统 Chrome → Edge → Playwright 自带 Chromium。
"""
from __future__ import annotations

import os
import sys
from typing import Any

LAUNCH_ARGS = [
    "--disable-blink-features=AutomationControlled",
    "--no-sandbox",
]

_AUTO_CHANNELS: tuple[str | None, ...] = ("chrome", "msedge", None)


def headed() -> bool:
    return os.environ.get("PLAYWRIGHT_HEADED", "").strip().lower() in (
        "1",
        "true",
        "yes",
    )


def channel_label(channel: str | None) -> str:
    return channel if channel else "chromium"


def _forced_channel() -> str | None | object:
    raw = os.environ.get("PATENT_BROWSER_CHANNEL", "").strip().lower()
    if not raw:
        return _AUTO
    if raw in ("chromium", "bundled", "playwright"):
        return None
    if raw in ("chrome", "msedge"):
        return raw
    return _AUTO


_AUTO = object()


def _chrome_executable() -> str | None:
    for key in ("CHROME_PATH", "PATENT_BROWSER_EXECUTABLE"):
        p = os.environ.get(key, "").strip()
        if p and os.path.isfile(p):
            return p
    return None


def launch_chromium(
    playwright: Any,
    *,
    headless: bool | None = None,
    extra_args: list[str] | None = None,
) -> tuple[Any, str]:
    if headless is None:
        headless = not headed()
    args = list(LAUNCH_ARGS)
    if extra_args:
        args.extend(extra_args)

    exe = _chrome_executable()
    if exe:
        browser = playwright.chromium.launch(
            headless=headless,
            args=args,
            executable_path=exe,
        )
        print(f"BROWSER: executable={exe}", file=sys.stderr, flush=True)
        return browser, "chromium"

    forced = _forced_channel()
    candidates: list[str | None] = (
        list(_AUTO_CHANNELS) if forced is _AUTO else [forced]  # type: ignore[list-item]
    )

    errors: list[str] = []
    for ch in candidates:
        kwargs: dict[str, Any] = {"headless": headless, "args": args}
        if ch:
            kwargs["channel"] = ch
        try:
            browser = playwright.chromium.launch(**kwargs)
            label = channel_label(ch)
            print(f"BROWSER: channel={label}", file=sys.stderr, flush=True)
            return browser, label
        except Exception as e:
            errors.append(f"{channel_label(ch)}: {e}")
            continue

    detail = " | ".join(errors[:3]) if errors else "unknown"
    raise RuntimeError(
        f"无法启动浏览器（{detail}）。请设置 CHROME_PATH 或安装 Chrome/Edge，"
        "或执行 python -m playwright install chromium"
    ) from None
