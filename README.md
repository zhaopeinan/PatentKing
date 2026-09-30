# PatentKing

<p align="center">
  <strong>专利王</strong><br/>
  面向成果转化的专利智能体<br/>
  A patent agent for technology transfer
</p>

<p align="center">
  <a href="#中文">中文</a> · <a href="#english">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.4"/>
  <img src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white" alt="Vue 3.5"/>
  <img src="https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white" alt="Docker Compose"/>
  <img src="https://img.shields.io/badge/License-MIT-green" alt="MIT License"/>
</p>

PatentKing 把一份技术材料推进到可以审阅的专利文稿。它跑在 [Apboa Next](https://github.com/huxuehao/apboa-next) 上：平台管模型、会话、技能和租户；本仓库管案件、交底流水线和专利工具。

PatentKing takes a technical write-up through to a patent draft you can review. It runs on [Apboa Next](https://github.com/huxuehao/apboa-next). The platform owns models, sessions, skills, and tenants. This repository owns matters, the disclosure pipeline, and the patent tooling.

模型密钥、交底范文和运行数据留在你的环境里。下面两节是同一套启动步骤，分别用中文和英文写完。

Model keys, disclosure samples, and runtime data stay in your environment. The two sections below are the same procedure, written out in full in each language.

## 中文

### 这个仓库做什么

PatentKing 是成果转化场景下的专利工作台，覆盖六件事：

| 能力 | 你得到什么 |
|------|------------|
| 交底撰写 | 发明点清单、交底书，以及 Word 导出 |
| 现有技术查新 | 对比文献检索，供新颖性判断使用 |
| 论文转专利 | 按论文材料整理的申请底稿 |
| 审查答复 | 创造性答辩提纲 |
| 侵权对照 | 按公开号整理的产品与权利要求对照 |
| 案件管理 | 按类型保存的版本、会话和产物 |

案件在侧栏「成果转化 → 案件」，地址是 `/web/#/patent-matters`。架构取舍写在 [docs/architecture/adr-001-matter-and-tools.md](docs/architecture/adr-001-matter-and-tools.md)。

### 启动之前

需要 Docker Engine 20 或更高版本，以及 Docker Compose v2。宿主机不必安装 JDK、Maven 或 Node：首次构建在镜像里完成。

单机编排包含 MySQL、Redis、pgvector，以及控制台、运行时、网关、WebSocket、Shell 代理、前端和专利工具。`docker/.env.simple` 里 Runtime 与 Gateway 的内存上限各是 4 GB。给 Docker 留出能撑住这组上限的内存；机器吃紧时先改这份文件里的 `*_MEM_LIMIT`。

### 启动

```bash
git clone https://github.com/zhaopeinan/PatentKing.git
cd PatentKing
bash scripts/pk-dev.sh build
```

这条命令会做三件事：确认 Docker 已经起来；把 `docker/.env.simple` 复制为 `docker/.env`；执行 `docker compose up -d --build`。拉起来的服务是：

| 容器 | 作用 | 宿主机端口 |
|------|------|------------|
| `apboa-mysql` | 主库 `apboa_next` | 3306 |
| `apboa-redis` | 缓存与锁 | 6379 |
| `apboa-pgvector` | 向量库 | 5432 |
| `apboa-console` | 管理 API，技能导入走这里 | 3060 |
| `apboa-runtime` | 智能体运行时 | 容器内 3061 |
| `apboa-gateway` | 工作流对外端口，使用宿主机网络 | 直接绑在本机 |
| `apboa-proxy` | Shell 执行代理 | 3062 |
| `apboa-websocket` | 推送 | 3064 |
| `patentking-patent-tools` | 查新、附图、Word | 3070 |
| `apboa-frontend` | Nginx | 80 |

第一次构建要拉取基础镜像，并在镜像内编译 Java 和前端，耗时会明显长于以后的启动。看状态：

```bash
bash scripts/pk-dev.sh status
```

浏览器打开 <http://localhost>。Nginx 把 `/` 重定向到 `/web/`。

本地初始账号是 `admin` / `Admin@123.com`。这是上游单机镜像自带的演示口令，只用于本机。服务一旦暴露到局域网或公网，先把口令换掉。

数据在 `docker/data/`。这个目录不进 Git。停止和拆除：

```bash
bash scripts/pk-dev.sh stop   # 停容器，数据还在
bash scripts/pk-dev.sh down   # 删除容器和网络，docker/data 仍保留
```

改端口时编辑 `docker/.env.simple` 的 `FRONTEND_PORT`，再执行一次 `build`。管理脚本每次启动都会用 `.env.simple` 覆盖 `docker/.env`。

### 登录之后

仓库不内置商业模型密钥。对话前先在控制台配好模型，再导入技能。顺序不要倒过来：导入脚本会把默认智能体绑到当前租户里第一个已启用的模型。

1. 进入「开发 → 模型供应商」，填写 Base URL 和 API Key。密钥进平台的租户密钥，不要写回仓库。
2. 进入「模型配置」，启用具体模型并确认连通。
3. 克隆专利技能。这五套仓库带有专利全文和评测集，因此不放进 PatentKing。`PatentRadar` 体积最大。

```bash
mkdir -p patent-skills
cd patent-skills
git clone https://github.com/handsomestWei/patent-disclosure-skill.git handsomestWei-patent-disclosure-skill
git clone https://github.com/AqooDer/patent-mining-disclosure-skill.git patent-mining-disclosure-skill
git clone https://github.com/fuyuxiang/patent-disclosure-skill.git fuyuxiang-patent-disclosure-skill
git clone https://github.com/7toCR/paper2patent.git paper2patent
git clone https://github.com/yuc16/PatentRadar.git PatentRadar
cd ..
bash scripts/pack-skills.sh
bash scripts/pk-import-skills.sh
```

`pk-import-skills.sh` 登录 `http://127.0.0.1:3060`，上传技能包，并创建四名智能体：交底、论文转专利、侵权对照、口述交底。账号可通过环境变量覆盖：

```bash
PK_USER=admin PK_PASS='your-password' bash scripts/pk-import-skills.sh
```

4. 打开「成果转化 → 案件」，新建案件，再从案件进入会话。
5. 交底附图默认可以用本机 mermaid 渲染。若要 Tokenlab 自动出图，到「系统设置 → PatentKing 生图」填写 API Key。Key 只存在服务端配置里。

自检：

```bash
curl -s http://127.0.0.1:3070/healthz
```

返回成功即表示专利工具已在监听。浏览器能登录，并且「案件」页能打开，单机环境就齐了。

### 镜像拉不动时

大陆网络访问 Docker Hub 经常超时。OrbStack 可以先写镜像加速再构建：

```bash
bash scripts/pk-dev.sh mirror --pull
bash scripts/pk-dev.sh build
```

其他 Docker 环境在 daemon 上配置 `registry-mirrors`，或设置 `docker/.env.simple` 里的 `DOCKER_REGISTRY`。细节在 [docs/dev-environment.md](docs/dev-environment.md)。

### 只改前端或后端时

日常使用不需要把服务拆开跑。要改 UI 或 Java 时，中间件仍用 Docker，应用在宿主机启动。步骤、端口和样例配置见 [docs/dev-environment.md](docs/dev-environment.md)。上游框架的原文说明在 [docs/apboa-framework.md](docs/apboa-framework.md)。多机部署见 [docker/README.md](docker/README.md)。

### 仓库里故意没有的东西

- 模型 API Key 和 Tokenlab 密钥。
- `ground_truth_file/` 下的交底范文，以及 `test/` 下的论文 PDF。目录说明还在，文件请自己放。
- `docker/data/`、`docker/logs/` 和 `docker/.env`。
- 第三方技能检出，以及其中的专利评测数据。

### 目录

| 路径 | 内容 |
|------|------|
| `biz/biz-pk-matter` | 案件、导出、查新和引导逻辑 |
| `docker/` | 单机编排与 `patent-tools` 镜像 |
| `ui/src/views/PatentKing` | 案件界面 |
| `patent-skills/tokenlab-imagegen` | 随仓库发布的出图技能 |
| `scripts/pk-dev.sh` | 启动、停止、专利工具和镜像加速 |
| `docs/` | 环境说明、架构决策、上游框架说明 |

### 上游

框架代码来自 Apboa Next，版权归 StudiousTiger，协议为 MIT。官方主站是 [Gitee](https://gitee.com/studioustiger/apboa-next)，镜像在 [GitHub](https://github.com/huxuehao/apboa-next)。

本仓库的 `origin` 是 PatentKing。框架远程保留为 `upstream`：

```bash
git pull upstream master
```

## English

### What this repository is

PatentKing is a patent workbench for technology transfer. It covers six jobs:

| Capability | What you get |
|------------|----------------|
| Disclosure drafting | An invention-point list, a disclosure, and a Word export |
| Prior-art search | Retrieved references for a novelty read |
| Paper to patent | An application draft prepared from a paper |
| Office-action reply | An outline for an inventive-step response |
| Infringement / FTO | A product-to-claim comparison for a publication number |
| Matter management | Versions, sessions, and artifacts kept by matter type |

Matters live under **成果转化 → 案件** in the sidebar, at `/web/#/patent-matters`. The design notes are in [docs/architecture/adr-001-matter-and-tools.md](docs/architecture/adr-001-matter-and-tools.md).

### Before you start

You need Docker Engine 20+ and Docker Compose v2. The host does not need JDK, Maven, or Node. The first build compiles them inside the images.

The single-node stack runs MySQL, Redis, pgvector, the console, the runtime, the gateway, WebSocket, the shell proxy, the frontend, and the patent tools. In `docker/.env.simple`, the runtime and the gateway are each capped at 4 GB. Give Docker enough memory to honor those caps. If the machine is tight, lower `*_MEM_LIMIT` in that file first.

### Start

```bash
git clone https://github.com/zhaopeinan/PatentKing.git
cd PatentKing
bash scripts/pk-dev.sh build
```

The script checks that Docker is up, copies `docker/.env.simple` to `docker/.env`, and runs `docker compose up -d --build`. The containers are:

| Container | Role | Host port |
|-----------|------|-----------|
| `apboa-mysql` | Primary database `apboa_next` | 3306 |
| `apboa-redis` | Cache and locks | 6379 |
| `apboa-pgvector` | Vector store | 5432 |
| `apboa-console` | Admin API, including skill import | 3060 |
| `apboa-runtime` | Agent runtime | 3061 inside the network |
| `apboa-gateway` | Workflow ports, on the host network | bound on the host |
| `apboa-proxy` | Shell execution proxy | 3062 |
| `apboa-websocket` | Push channel | 3064 |
| `patentking-patent-tools` | Search, figures, and Word export | 3070 |
| `apboa-frontend` | Nginx | 80 |

The first build pulls base images and compiles Java and the frontend inside those images. Later starts are much shorter. Watch it with:

```bash
bash scripts/pk-dev.sh status
```

Open <http://localhost>. Nginx redirects `/` to `/web/`.

The local bootstrap account is `admin` / `Admin@123.com`. It ships with the upstream single-node image for local use. Change it before the service is reachable beyond your machine.

Data is stored in `docker/data/`, which is not committed. Stop and tear down with:

```bash
bash scripts/pk-dev.sh stop   # stop containers, keep data
bash scripts/pk-dev.sh down   # remove containers and the network, keep docker/data
```

To move the site off port 80, set `FRONTEND_PORT` in `docker/.env.simple` and run `build` again. The helper overwrites `docker/.env` from `.env.simple` on each start.

### After you sign in

This repository does not ship a commercial model key. Configure a model before you import skills. The import binds the default agents to the first enabled model in the tenant, so the order matters.

1. Open **开发 → 模型供应商** and enter the base URL and API key. The key is stored in the tenant secret store. Do not commit it.
2. Open **模型配置**, enable a concrete model, and confirm it connects.
3. Clone the patent skills. Those five repositories carry patent texts and evaluation sets, so they are not vendored here. `PatentRadar` is the large one.

```bash
mkdir -p patent-skills
cd patent-skills
git clone https://github.com/handsomestWei/patent-disclosure-skill.git handsomestWei-patent-disclosure-skill
git clone https://github.com/AqooDer/patent-mining-disclosure-skill.git patent-mining-disclosure-skill
git clone https://github.com/fuyuxiang/patent-disclosure-skill.git fuyuxiang-patent-disclosure-skill
git clone https://github.com/7toCR/paper2patent.git paper2patent
git clone https://github.com/yuc16/PatentRadar.git PatentRadar
cd ..
bash scripts/pack-skills.sh
bash scripts/pk-import-skills.sh
```

`pk-import-skills.sh` signs in to `http://127.0.0.1:3060`, uploads the skill bundle, and creates four agents: disclosure, paper-to-patent, infringement comparison, and conversational disclosure. Override the account if you have changed it:

```bash
PK_USER=admin PK_PASS='your-password' bash scripts/pk-import-skills.sh
```

4. Open **成果转化 → 案件**, create a matter, and start the session from that matter.
5. Disclosure figures can be rendered locally with mermaid. For Tokenlab image generation, open **系统设置 → PatentKing 生图** and save the API key. The key stays in server-side settings.

Check the patent tools:

```bash
curl -s http://127.0.0.1:3070/healthz
```

A successful response means the sidecar is listening. If the browser login works and the matter page opens, the single-node install is up.

### When image pulls stall

Pulls from Docker Hub often time out on networks in mainland China. On OrbStack:

```bash
bash scripts/pk-dev.sh mirror --pull
bash scripts/pk-dev.sh build
```

Elsewhere, set `registry-mirrors` on the Docker daemon, or set `DOCKER_REGISTRY` in `docker/.env.simple`. See [docs/dev-environment.md](docs/dev-environment.md).

### Hacking on the UI or the Java services

Day-to-day use does not require splitting the stack. When you do change the UI or the backend, keep the middleware in Docker and run the app on the host. Ports, sample configs, and the order of processes are in [docs/dev-environment.md](docs/dev-environment.md). The upstream framework write-up is archived in [docs/apboa-framework.md](docs/apboa-framework.md). Multi-node deployment is in [docker/README.md](docker/README.md).

### Left out on purpose

- Model API keys and the Tokenlab key.
- Disclosure samples under `ground_truth_file/`, and paper PDFs under `test/`. The notes remain; bring your own files.
- `docker/data/`, `docker/logs/`, and `docker/.env`.
- Third-party skill checkouts and the patent evaluation data inside them.

### Layout

| Path | Contents |
|------|----------|
| `biz/biz-pk-matter` | Matters, export, prior-art search, and bootstrap |
| `docker/` | The single-node stack and the `patent-tools` image |
| `ui/src/views/PatentKing` | The matter UI |
| `patent-skills/tokenlab-imagegen` | The figure-generation skill shipped here |
| `scripts/pk-dev.sh` | Start, stop, patent tools, and registry mirrors |
| `docs/` | Environment notes, the architecture decision, and the upstream guide |

### Upstream

The framework is Apboa Next, copyright StudiousTiger, MIT licensed. Upstream publishes on [Gitee](https://gitee.com/studioustiger/apboa-next) and mirrors to [GitHub](https://github.com/huxuehao/apboa-next).

`origin` on this clone is PatentKing. The framework remote is kept as `upstream`:

```bash
git pull upstream master
```

## License

[MIT](LICENSE). The Apboa Next portions remain copyright StudiousTiger. See the notice in `LICENSE`.
