#!/usr/bin/env python3
"""Generate images with Tokenlab gpt-image-2 and save base64 output.

Reads API key from TOKENLAB_API_KEY by default. The key is never printed and is not
written to request/response files.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

DEFAULT_BASE_URLS = ["https://api.tokenlab.cc.cd/v1", "https://hk.tokenlab.ccwu.cc/v1"]
SIZES = ["auto", "1024x1024", "1536x1024", "1024x1536", "2048x2048", "2048x1152", "1152x2048", "3840x2160", "2160x3840"]
QUALITIES = ["auto", "low", "medium", "high"]
FORMATS = ["png", "jpeg", "webp"]


def eprint(*args: Any) -> None:
    print(*args, file=sys.stderr)


def read_prompt(args: argparse.Namespace) -> str:
    parts: List[str] = []
    if args.prompt:
        parts.append(args.prompt)
    if args.prompt_file:
        parts.append(Path(args.prompt_file).expanduser().read_text(encoding="utf-8"))
    if args.stdin:
        parts.append(sys.stdin.read())
    prompt = "\n\n".join(p.strip() for p in parts if p and p.strip()).strip()
    if not prompt:
        raise SystemExit("ERROR: provide --prompt, --prompt-file, or --stdin")
    return prompt


def build_request(args: argparse.Namespace, prompt: str) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "model": args.model,
        "prompt": prompt,
        "n": args.n,
        "size": args.size,
        "quality": args.quality,
        "output_format": args.output_format,
    }
    if args.output_compression is not None:
        payload["output_compression"] = args.output_compression
    return payload


def curl_network_args(args: argparse.Namespace) -> List[str]:
    mode = args.network
    if mode == "direct":
        return ["--noproxy", "*"]
    if mode == "env":
        return []
    if mode == "http-proxy":
        if not args.proxy:
            raise SystemExit("ERROR: --network http-proxy requires --proxy, e.g. http://127.0.0.1:7890")
        return ["--proxy", args.proxy]
    if mode == "socks5-proxy":
        if not args.proxy:
            raise SystemExit("ERROR: --network socks5-proxy requires --proxy, e.g. 127.0.0.1:7890 or socks5://127.0.0.1:7890")
        proxy = args.proxy
        if proxy.startswith("socks5://"):
            proxy = proxy[len("socks5://"):]
        return ["--socks5-hostname", proxy]
    raise SystemExit(f"ERROR: unknown network mode: {mode}")


def run_curl(base_url: str, api_key: str, request_file: Path, response_file: Path, args: argparse.Namespace) -> Tuple[bool, str, str]:
    endpoint = base_url.rstrip("/") + "/images/generations"
    cmd = [
        "curl", "-sS", "--no-buffer", "--max-time", str(args.timeout),
        *curl_network_args(args),
        "-X", "POST", endpoint,
        "-H", f"Authorization: Bearer {api_key}",
        "-H", "Content-Type: application/json",
        "--data-binary", f"@{request_file}",
        "-o", str(response_file),
        "-w", "%{http_code}",
    ]
    safe_cmd = ["curl", "-sS", "--no-buffer", "--max-time", str(args.timeout), "...", endpoint, "..."]
    eprint("+", " ".join(safe_cmd))
    proc = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    http_code = (proc.stdout or "").strip()[-3:]
    if proc.returncode != 0:
        return False, http_code, proc.stderr.strip() or f"curl exit {proc.returncode}"
    if not http_code.startswith("2"):
        body = response_file.read_text(encoding="utf-8", errors="replace")[:1000] if response_file.exists() else ""
        return False, http_code, body
    return True, http_code, ""


def decode_response(response_file: Path, output: Path, save_response: Optional[Path]) -> None:
    text = response_file.read_text(encoding="utf-8", errors="replace")
    try:
        data = json.loads(text)
        b64 = data["data"][0]["b64_json"]
    except Exception as exc:
        snippet = text[:1000]
        raise SystemExit(f"ERROR: could not parse Tokenlab image response: {exc}\nResponse head:\n{snippet}")
    image_bytes = base64.b64decode(b64)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(image_bytes)
    if save_response:
        save_response.parent.mkdir(parents=True, exist_ok=True)
        save_response.write_text(text, encoding="utf-8")
    print(f"saved: {output}")
    print(f"bytes: {len(image_bytes)}")


def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(description="Generate an image via Tokenlab gpt-image-2")
    p.add_argument("--prompt", help="Prompt text")
    p.add_argument("--prompt-file", help="UTF-8 prompt file")
    p.add_argument("--stdin", action="store_true", help="Read prompt from stdin")
    p.add_argument("--output", required=True, help="Output image path")
    p.add_argument("--save-response", help="Optional path to save raw JSON response")
    p.add_argument("--save-request", help="Optional path to save sanitized request JSON without API key")
    p.add_argument("--model", default="gpt-image-2")
    p.add_argument("--size", default="1024x1024", choices=SIZES)
    p.add_argument("--quality", default="high", choices=QUALITIES)
    p.add_argument("--output-format", default="png", choices=FORMATS)
    p.add_argument("--output-compression", type=int, help="0-100, only for jpeg/webp")
    p.add_argument("--n", type=int, default=1)
    p.add_argument("--base-url", action="append", help="Override/add base URL; may be repeated")
    p.add_argument("--api-key-env", default="TOKENLAB_API_KEY")
    p.add_argument("--timeout", type=int, default=650)
    p.add_argument("--network", choices=["direct", "env", "http-proxy", "socks5-proxy"], default="direct")
    p.add_argument("--proxy", help="Proxy URL/address for http-proxy or socks5-proxy mode")
    p.add_argument("--dry-run", action="store_true", help="Write request JSON and exit without calling API")
    args = p.parse_args(argv)

    api_key = os.environ.get(args.api_key_env)
    if not api_key and not args.dry_run:
        raise SystemExit(f"ERROR: {args.api_key_env} is missing. Set it without printing the value, e.g. export {args.api_key_env}=YOUR_TOKENLAB_API_KEY")

    prompt = read_prompt(args)
    payload = build_request(args, prompt)
    output = Path(args.output).expanduser().resolve()
    save_response = Path(args.save_response).expanduser().resolve() if args.save_response else None

    with tempfile.TemporaryDirectory(prefix="tokenlab-imagegen-") as td:
        request_file = Path(args.save_request).expanduser().resolve() if args.save_request else Path(td) / "request.json"
        response_file = save_response if save_response else Path(td) / "response.json"
        request_file.parent.mkdir(parents=True, exist_ok=True)
        request_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        if args.dry_run:
            print(f"dry-run request: {request_file}")
            print(f"output would be: {output}")
            return 0

        base_urls = args.base_url or DEFAULT_BASE_URLS
        last_error = ""
        start = time.time()
        for base_url in base_urls:
            ok, http_code, err = run_curl(base_url, api_key, request_file, response_file, args)
            if ok:
                decode_response(response_file, output, save_response)
                print(f"base_url: {base_url}")
                print(f"elapsed_seconds: {time.time() - start:.1f}")
                return 0
            last_error = f"{base_url} HTTP {http_code}: {err}"
            eprint("failed:", last_error[:1200])
        raise SystemExit("ERROR: all Tokenlab endpoints failed. Last error: " + last_error[:1200])


if __name__ == "__main__":
    raise SystemExit(main())
