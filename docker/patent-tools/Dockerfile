# PatentKing patent-tools sidecar
# Build from repo root:
#   docker build -f docker/patent-tools/Dockerfile .
FROM python:3.12-slim-bookworm

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PUPPETEER_SKIP_DOWNLOAD=true \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    CHROME_PATH=/usr/bin/chromium \
    PK_SKILL_TOOLS=/app/vendor \
    PK_CNIPA_PATENT_TYPE=invention \
    EPUB_WAF_MAX_WAIT_SEC=180

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg \
      chromium \
      fonts-noto-cjk fonts-wqy-zenhei \
      && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
      && apt-get install -y --no-install-recommends nodejs \
      && rm -rf /var/lib/apt/lists/* \
      && npm install -g @mermaid-js/mermaid-cli@11.4.0 \
      && mmdc --version

COPY docker/patent-tools/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY docker/patent-tools/app ./app
COPY docker/patent-tools/puppeteer-config.json /app/puppeteer-config.json
COPY docker/patent-tools/vendor/shared/browser.py ./vendor/shared/browser.py
COPY patent-skills/handsomestWei-patent-disclosure-skill/tools/crawl/cnipa_epub_crawler.py ./vendor/crawl/
COPY patent-skills/handsomestWei-patent-disclosure-skill/tools/crawl/cnipa_epub_parse.py ./vendor/crawl/
COPY patent-skills/handsomestWei-patent-disclosure-skill/tools/shared/patent_type.py ./vendor/shared/
COPY patent-skills/handsomestWei-patent-disclosure-skill/tools/shared/stdio_utf8.py ./vendor/shared/
COPY patent-skills/patent-mining-disclosure-skill/tools/mermaid_render.py ./vendor/
COPY patent-skills/patent-mining-disclosure-skill/tools/md_to_docx.py ./vendor/
COPY patent-skills/patent-mining-disclosure-skill/tools/math_render.py ./vendor/

# mmdc 默认不以 root 跑 Chromium；统一注入 --no-sandbox 配置
RUN printf '%s\n' \
      '#!/bin/sh' \
      'exec node /usr/lib/node_modules/@mermaid-js/mermaid-cli/src/cli.js -p /app/puppeteer-config.json "$@"' \
      > /usr/local/bin/mmdc \
    && chmod +x /usr/local/bin/mmdc \
    && mmdc --version

EXPOSE 3070
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "3070"]
