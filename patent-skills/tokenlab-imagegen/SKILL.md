---
name: tokenlab-imagegen
description: Use this skill when the user wants to generate images through the Tokenlab gpt-image-2 API, mentions Tokenlab image generation, asks to reuse the saved image API method, or wants a local workflow so they do not need to restate Base URL, authentication, request JSON, timeout, and base64 decoding details. Handles text-to-image requests, prompt shaping, API invocation, base64 image saving, and basic validation.
metadata:
  short-description: Tokenlab gpt-image-2 生图
---

# Tokenlab ImageGen

## Purpose

Generate images via Tokenlab's OpenAI-compatible `gpt-image-2` API without asking the user to repeat the API instructions. Use the bundled script instead of hand-writing curl unless a one-off debug command is needed.

## Defaults

- Auth env var: `TOKENLAB_API_KEY`
- Primary Base URL: `https://api.tokenlab.cc.cd/v1`
- Fallback Base URL: `https://hk.tokenlab.ccwu.cc/v1`
- Endpoint: `POST /images/generations`
- Model: `gpt-image-2`
- Timeout: at least 650 seconds
- Response format: JSON with `data[0].b64_json`; decode and save to an image file
- Default output format: `png`
- Default quality: `high`
- Prefer `1024x1024` for first drafts or unstable network; use `2048x2048` / `3840x2160` only when the user needs high resolution.

Supported sizes: `auto`, `1024x1024`, `1536x1024`, `1024x1536`, `2048x2048`, `2048x1152`, `1152x2048`, `3840x2160`, `2160x3840`.

## Workflow

1. Confirm `TOKENLAB_API_KEY` exists without printing it:
   ```bash
   [ -n "${TOKENLAB_API_KEY:-}" ] && echo present || echo missing
   ```
2. Shape the user prompt into a concise production spec. Preserve the user's style constraints and subject matter; add only practical composition, output, and negative-prompt details that improve controllability.
3. Save the final prompt to a project file when it is non-trivial.
4. Run the helper:
   ```bash
   python patent-skills/tokenlab-imagegen/scripts/tokenlab_imagegen.py \
     --prompt-file /absolute/path/prompt.txt \
     --output /absolute/path/output.png \
     --size 1024x1024 \
     --quality high \
     --save-response /absolute/path/response.json
   ```
5. Validate with `file`, `sips -g pixelWidth -g pixelHeight`, or the project viewer. In the Codex desktop app, show the result with an absolute Markdown image path.

## Prompt shaping rules

- Keep the user's requested style, palette, subject, aspect ratio, and constraints.
- For dense illustrations, structure the prompt as: theme, composition, foreground subjects, middle/background elements, style constraints, negatives.
- Add negative constraints when helpful: no readable text, no watermark, no photorealism, no black heavy outlines, etc.
- Avoid naming a living artist as a direct imitation target. Convert it to a descriptive style phrase, e.g. “modern Chinese editorial flat decorative illustration” instead of “in X artist's exact style.”

## Network notes

The helper uses `curl` because it has been more reliable than Python `requests` for long Tokenlab image calls in this environment. Default network mode is direct (`--noproxy '*'`). If direct fails and the user's network requires a proxy, retry with:

```bash
python patent-skills/tokenlab-imagegen/scripts/tokenlab_imagegen.py \
  --prompt-file /absolute/path/prompt.txt \
  --output /absolute/path/output.png \
  --network env
```

or with an explicit proxy:

```bash
python patent-skills/tokenlab-imagegen/scripts/tokenlab_imagegen.py \
  --prompt-file /absolute/path/prompt.txt \
  --output /absolute/path/output.png \
  --network http-proxy \
  --proxy http://127.0.0.1:7890
```

## Safety and secrecy

- Never print, log, or write the API key to files.
- Do not embed the API key in `SKILL.md`, prompts, request JSON files, or response files.
- If the key is missing, give the user a shell command using a placeholder, not their real key.
