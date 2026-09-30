# ADR-001：案件域模型 & 专利工具部署形态

- **Status:** Proposed → **Accepted（默认拍板，可复议）**
- **Date:** 2026-08-19
- **Context:** PatentKing 开发计划 Phase 0 决策点；见 `docs/plans/2026-08-19-patentking-roadmap.md`

---

## 决策 A：案件模型 — **新建 `biz-pk-matter`**

### 选项

| 方案 | 做法 | 优点 | 缺点 |
|------|------|------|------|
| A1 文件夹+标签 | 仅用 ChatSession / Workspace 目录 + 标签 | 零开发、马上能聊 | 无版本/产物索引/权限边界；P2 预警与估值难挂靠 |
| A2 扩展 AgentSession | 在现有会话表加字段 | 改动小 | 污染上游 Apboa 模型；跟上游合并成本高 |
| **A3 新建 `biz-pk-matter`（采纳）** | 独立业务模块 + Flyway 表 | 边界清晰、可跟上游；支撑全生命周期 | 首期多几张表/CRUD |

### 决定

**采纳 A3：新建 `biz-pk-matter` 模块。**

### 理由

1. 需求覆盖交底 → 五书 → 侵权 → 估值 → 尽调，天然是「案件」生命周期，不是一次聊天。
2. Apboa 已按 `biz-*` 扁平域拆分；专利业务进独立模块，**少改 `engine` / `biz-agent` 核心**，便于跟上游。
3. AqooDer 的 `vN-时间戳/`、产物清单、发明人元信息需要结构化落库，标签方案必然返工。
4. 后续 Watchlist / 估值报告都要 `matter_id` 外键；现在建表比以后迁移便宜。

### 首期表结构（最小集）

```text
pk_matter
  id, tenant_id, title, matter_type, status,
  agent_definition_id (nullable), workspace_rel_path,
  inventors_json, meta_json, created_by, created_at, updated_at

pk_matter_version
  id, matter_id, version_no, label, path_rel, note, created_at

pk_artifact
  id, matter_id, version_id (nullable), artifact_type,
  name, storage_uri, mime, checksum, meta_json, created_at
```

`matter_type` 一级枚举（有独立流水线才建类）：

- `disclosure` — 交底撰写（挖点→查新→成文；口述场景绑定 `pk-disclosure-lite`）
- `paper2patent` — 论文转专利
- `radar` — 侵权 / FTO
- `oa` — 审查答复

细意图（解读 / 估值 / 尽调 / 供需匹配等）写入 `metaJson.scene`，由对话意图或建案「场景」选择；**不再**作为一级 `matter_type`。

历史值 `read|valuate|dd|match`：列表兼容展示，创建时归一为 `disclosure` + 对应 scene。

`status` 建议：`draft` | `in_progress` | `awaiting_confirm` | `delivered` | `archived`

Workspace 物理目录仍走 Apboa `.apboa/.../workspaces`；**库表只存相对路径与索引**，不重复存全文。

### 首期不做

- 复杂审批流、计费、跨租户案件转让（P2+）
- 与外部 DMS 同步

### 后果

- Phase 1 Task 1.1 按本 ADR 建模块；UI 增加 `PatentKing/Matter` 菜单。
- Chat 从案件详情「打开会话」，会话可写回 `pk_matter.meta_json.session_ids`。

---

## 决策 B：专利工具 — **独立 `patent-tools` Sidecar（B）**

### 选项

| 方案 | 做法 | 优点 | 缺点 |
|------|------|------|------|
| B1 Runtime 内嵌 Python | 在 `runner-runtime` 镜像装 Playwright/mermaid/docx | 部署少一个容器 | 镜像膨胀、依赖冲突、扩容拖垮推理、难独立升级 |
| **B2 Sidecar `patent-tools`（采纳）** | 独立 HTTP 服务；Apboa 以 Tool/MCP 调用 | 依赖隔离、可单独扩缩、Skill 脚本原样复用 | 多一个服务与鉴权 |
| B3 仅用 LLM 不跑脚本 | 不用 CNIPA/mermaid 脚本 | 最简单 | 查新/附图质量不可控，偏离已验证 Skill |

### 决定

**采纳 B2：独立 Sidecar；Apboa Tool 调用。本地开发可先用「本机 Python venv + HTTP」等价物。**

### 理由

1. 五套 Skill 依赖异构：Playwright(Chromium)、mermaid-cli、LibreOffice、PatentRadar 搜索 Key、可选 CAD — 与 Java Runtime 生命周期不同。
2. Apboa 已有 `runner-proxy` 沙箱哲学：**危险/重型执行与推理进程分离**；Sidecar 同一思路。
3. Runtime 弹性扩容时，不应每个推理副本都带一份 Chromium。
4. Skill 里的 `tools/*.py` 可几乎原样挂到 sidecar 路由，适配成本最低。

### 形态约定

```text
patent-tools (FastAPI 或同类)
  POST /v1/cnipa/search
  POST /v1/office/docx-to-md | pptx-to-md
  POST /v1/render/mermaid
  POST /v1/export/md-to-docx | docx-to-pdf
  POST /v1/drawings/*          # paper2patent / 线稿辅助
  POST /v1/radar/*             # 可选：后续把 PatentRadar 模式A 挂这里
  GET  /healthz
```

- 鉴权：内网 Token 或 mTLS；密钥（搜索 API、生图）只存在 sidecar / 租户 SK，不进前端。
- Console 注册为 **全局 Tool 或 MCP Server**；Agent Skill 内 Bash 改为「调平台 Tool」（包装层），避免 Agent 直连任意外网脚本路径。
- Docker Compose：`docker/patent-tools/` + 挂入 `docker-compose-simple.yml` 可选 profile。

### 本地开发过渡

Phase 0–1 允许：

```text
本机: uvicorn patent_tools.main:app --port 3070
Runtime application-dev.yml: pk.tools.base-url=http://127.0.0.1:3070
```

与 Compose sidecar **同一 OpenAPI**，避免两套协议。

### 首期不做

- 不把 PatentRadar 完整模式 A（多搜索引擎 + Dashboard）塞进第一版 sidecar；先 Skill 四段 Workflow。
- 不在 sidecar 内跑大模型推理。

### 后果

- Phase 0 Task 0.3 关闭：本 ADR 即结论。
- Phase 1 查新/导出必须经 sidecar（或明确降级开关 `pk.tools.degrade-websearch=true`）。

---

## 联合后果（对路线图的锁定）

| 项 | 锁定值 |
|----|--------|
| 案件 | `biz/biz-pk-matter` + 三表最小集 |
| 工具 | `patent-tools` sidecar，OpenAPI，Tool/MCP 接入 |
| Skill | 仍导入原包；执行脚本经包装层改调 sidecar |
| 与上游 | 不修改 Apboa 会话表结构；案件模块可独立演进 |

---

## 复议条件

出现以下情况可重开 ADR：

- 单机交付强制「只准一个 JVM 容器」且运维拒绝 sidecar → 可临时退回 B1（须接受镜像体积）。
- 上游 Apboa 官方提供「Matter/Project」一等实体 → 评估迁移而非长期双轨。
