# PatentKing / Apboa 本地开发环境

**原则：** 平台与中间件一律 Docker；专利脚本工具用独立 venv / sidecar 镜像；**不要**往 macOS 系统 Python / 全局 Maven 仓库硬塞项目依赖。LLM API Key **由用户在控制台配置**，不写进仓库、不写进 `.env` 默认值。

---

## 0. Docker 国内镜像（大陆网络必看）

OrbStack 已支持在 `~/.orbstack/config/docker.json` 配置 `registry-mirrors`。本仓库默认推荐：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run",
    "https://dockercf.jsdelivr.fyi",
    "https://docker.jsdelivr.fyi"
  ]
}
```

生效：

```bash
orb restart docker
docker info | grep -A 10 "Registry Mirrors"
```

若仍 EOF/超时，用显式镜像预拉取（会 retag 成 `mysql:8.0` 等本地名）：

```bash
bash scripts/pk-docker-mirror-pull.sh
bash scripts/pk-dev.sh build
```

一键配置 mirrors 并预拉取：

```bash
bash scripts/pk-dev.sh mirror --pull
```

---

## 1. 一键体验（推荐 Phase 0）

```bash
cd docker
bash start-simple.sh build    # 首次构建较久（Maven + 前端）
bash start-simple.sh status
```

- 访问：http://localhost（或 `FRONTEND_PORT`）
- 账号：`admin` / `Admin@123.com`
- 配置来源：`docker/.env.simple` → 启动时复制为 `docker/.env`

停止：

```bash
bash start-simple.sh stop
# 或彻底删除容器（保留 data 卷除非手动删）
bash start-simple.sh down
```

中间件数据默认在 `docker/data/`（MySQL / Redis / pgvector），已由 `.gitignore` 忽略则勿提交。

---

## 2. LLM API：用户自备，界面配置

系统**不**内置商业模型 Key。登录后在控制台配置：

| 步骤 | 菜单位置（Apboa UI） |
|------|----------------------|
| 1 | **模型供应商** — 新增 OpenAI / DashScope / Anthropic / Gemini / Ollama / OrcaRouter 等 |
| 2 | 填写 **Base URL + API Key**（Key 存平台密钥体系，勿提交 Git） |
| 3 | **模型配置** — 绑定具体模型名（如 `gpt-4.1`、`qwen-max`） |
| 4 | **智能体** — 为 Agent 选择所用模型 |

路由参考：`ui/src/router/modules/biz.ts`（`模型供应商` / `模型配置`）。

PatentKing 后续业务 Agent（交底 / 五书 / 侵权）一律引用上述租户级模型配置，**不在 Skill 或 sidecar 里写死 Key**。

---

## 3. patent-tools Sidecar（venv 或 Docker）

按 ADR-001：查新 / Office 转换 / mermaid / docx 与 Runtime 隔离。

### 3.1 本机 venv（开发调试）

```bash
cd docker/patent-tools
python3 -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -U pip
pip install -r requirements.txt
uvicorn app.main:app --host 127.0.0.1 --port 3070 --reload
```

探测：`curl -s http://127.0.0.1:3070/healthz`

`.venv/` 不得提交；见仓库 `.gitignore`。

### 3.2 Docker（与 Apboa 同网）

先确保 simple 栈网络已存在，再：

```bash
cd docker
docker compose -f docker-compose-patent-tools.yml --profile patent-tools up -d --build
curl -s http://127.0.0.1:3070/healthz
```

---

## 4. 依赖隔离一览

| 组件 | 隔离方式 |
|------|----------|
| MySQL / Redis / pgvector | Docker 容器 |
| Console / Runtime / WS / Proxy / Gateway / Frontend | Docker 容器 |
| patent-tools | Docker **或** 专用 `.venv` |
| 前端本地二次开发（可选） | `ui` 目录 `pnpm`，勿与系统 Node 全局包混用；推荐 `corepack enable && pnpm@9` |
| 后端本地二次开发（可选） | 仅中间件 Docker + JDK 21；Maven 用项目/`docker/maven/settings.xml` |

---

## 5. Phase 0 验收清单

- [ ] `bash start-simple.sh status` 核心容器 healthy
- [ ] 浏览器可登录 admin
- [ ] 对话可传附件（默认已写入一条 LOCAL 存储：`/app/.apboa/storage`；也可在「运维管理 → 存储管理」自行配置）
- [ ] 模型供应商页可打开（Key 可稍后填）
- [ ] `patent-tools` `/healthz` 返回 ok（venv 或 compose 任一）

---

## 7. 导入专利 Skill 包

```bash
bash scripts/pack-skills.sh
```

产物：`patent-skills/_packaged/patentking-skills.zip`。登录控制台 → **技能** → 上传 ZIP，分类填 `pk`。

含：交底（handsomestWei）、挖点门禁（AqooDer）、口述轻量（fuyuxiang）、论文转五书（paper2patent）、侵权分析（PatentRadar skill）。


- **端口占用**：改 `.env.simple` 中 `FRONTEND_PORT` / 映射端口后重建。
- **首次构建失败（拉镜像慢）**：配置镜像加速或 `DOCKER_REGISTRY`，清理后 `bash start-simple.sh rebuild`。
- **Mac 上 HOST_IP**：`start-simple.sh` 检测失败时回退 `127.0.0.1`，单机体验一般可用。
